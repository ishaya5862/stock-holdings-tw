/*
 * 持股記帳 2.0：買進、賣出、修改的表單（D-53；原本分別寫在 buy.html、sell.html、edit.html 裡）。
 *
 * 同一份程式用在兩個地方：
 * 1. 獨立頁面（/v2/buy、/v2/sell?code=、/v2/trades/{id}/edit）：頁面最後呼叫 V2Forms.initPage()。
 *    直接打網址、或 JavaScript 對話框不能用時，照樣可以操作。
 * 2. 對話框（總覽、明細頁）：點有 data-v2-dialog 的連結 → 向伺服器拿同一頁的表單片段（?dialog=1）→ 放進對話框。
 *    不用換頁；手機上對話框是全螢幕。
 *
 * 為什麼表單程式要「在 root 裡找欄位」：同一份程式要能放進對話框，不能假設整頁只有它。
 * （同一時間只會有一個表單，v2-form.js 用固定 id 找回饋訊息的部分不會衝突。）
 * 伺服器的資料放在表單片段裡的 <script type="application/json" class="v2-form-data">：
 * 放進對話框的 HTML 裡的 <script> 不會執行，所以不能像原本用 window.V2_PAGE。
 */
(function () {
	'use strict';

	/**
	 * @param root    表單片段（有 data-v2-form 的元素）
	 * @param options { inDialog: 是否在對話框裡, close: 關閉對話框 }
	 */
	function context(root, options) {
		const opts = options || {};
		return {
			el: function (id) {
				return root.querySelector('#' + id);
			},
			data: JSON.parse(root.querySelector('.v2-form-data').textContent),
			inDialog: Boolean(opts.inDialog),
			/** 成功後跳去哪裡：對話框 → 重新整理目前這一頁（總覽或明細，顯示最新數字）；獨立頁面 → 原本的去處 */
			redirect: function (pageDefault) {
				return opts.inDialog ? window.location.pathname + window.location.search : pageDefault;
			},
			/** 「取消」：對話框裡是關閉對話框；獨立頁面照連結回去 */
			bindCancel: function () {
				const cancel = root.querySelector('[data-v2-cancel]');
				if (cancel && opts.inDialog) {
					cancel.addEventListener('click', function (event) {
						event.preventDefault();
						opts.close();
					});
				}
			}
		};
	}

	// ===== 買進 =====
	function buy(root, options) {
		const ctx = context(root, options);
		const PAGE = ctx.data;
		const form = ctx.el('buy-form');
		const code = ctx.el('code');
		const date = ctx.el('date');
		const price = ctx.el('price');
		ctx.bindCancel();

		date.value = V2Form.todayInTaipei();

		// 從持股列點進來（?code=2330）：代號固定、不能改
		if (PAGE.fixedCode) {
			code.value = PAGE.fixedCode;
			code.readOnly = true;
			code.setAttribute('data-always-readonly', '');
			ctx.el('code-hint').textContent = '從持股進入，代號不能修改';
			const held = PAGE.held[PAGE.fixedCode];
			const title = '買進 ' + PAGE.fixedCode + (held ? ' ' + held.name : '');
			ctx.el('title').textContent = title;
			if (!ctx.inDialog) {
				document.title = title + ' - 持股記帳 2.0';
			}
		} else {
			const hint = ctx.el('code-hint');
			const defaultHint = hint.textContent;
			let lookedUp = ''; // 已經查過（或正在查）的代號：同一個代號不重複查

			V2Form.bindCode(code, function () {
				// 代號改了：說明回到原本的文字，離開輸入框時再查新的代號
				if (code.value !== lookedUp) {
					lookedUp = '';
					setHint(defaultHint, '');
				}
				showHoldingInfo();
				recalc();
			});
			code.addEventListener('blur', function () {
				if (code.value === '') {
					return;
				}
				const error = V2Form.checkCode(code.value);
				V2Form.showError(code, error);
				if (!error && code.value !== lookedUp) {
					lookupCode(code.value);
				}
			});

			/** 代號下面的說明：一般文字、或「圖示＋文字＋市場別標籤」 */
			function setHint(text, colorClass, iconClass, market) {
				hint.className = 'form-text ' + (colorClass || '');
				hint.replaceChildren();
				if (iconClass) {
					const icon = document.createElement('i');
					icon.className = iconClass;
					hint.appendChild(icon);
				}
				hint.appendChild(document.createTextNode(text)); // textContent：伺服器的名稱不當成 HTML
				if (market) {
					const badge = document.createElement('span');
					badge.className = 'v2-market ms-1 ' + (market === 'TWSE' ? 'v2-market-twse' : 'v2-market-tpex');
					badge.textContent = market === 'TWSE' ? '上市' : '上櫃';
					hint.appendChild(badge);
				}
			}

			/**
			 * 離開代號輸入框就查（D-55）：馬上顯示名稱與上市／上櫃，打錯代號可以立刻發現。
			 * 查無只是提醒、不擋送出：補登已下市股票的舊交易時，送出會改查「交易日期那個月」。
			 */
			function lookupCode(value) {
				lookedUp = value;
				setHint('查詢中…（向證交所、櫃買中心確認代號，最多約 10 秒）', 'text-secondary', 'spinner-border spinner-border-sm me-1');
				fetch('/v2/stocks/' + encodeURIComponent(value), { headers: { 'Accept': 'application/json' } })
					.then(function (response) {
						return response.json().then(function (body) { return { status: response.status, body: body }; });
					})
					.then(function (result) {
						if (code.value !== value) {
							return; // 查詢中使用者又改了代號：這個結果已經沒用
						}
						if (result.status === 200) {
							setHint(result.body.name, 'text-success', 'bi bi-check-circle-fill me-1', result.body.market);
						} else if (result.status === 404) {
							setHint(result.body.error.message + '。如果是補登已下市股票的舊交易，送出時會改查交易日期那個月。',
								'text-warning-emphasis', 'bi bi-exclamation-triangle me-1');
						} else {
							setHint('官方行情暫時連不上，送出時會再確認代號', 'text-warning-emphasis', 'bi bi-exclamation-triangle me-1');
						}
					})
					.catch(function () {
						if (code.value === value) {
							setHint('暫時無法查詢代號，送出時會再確認', 'text-warning-emphasis', 'bi bi-exclamation-triangle me-1');
						}
					});
			}
		}

		const quantity = V2Form.bindQuantity({
			input: ctx.el('quantity'),
			unitName: 'unit',
			hint: ctx.el('quantity-hint'),
			onChange: recalc
		});
		ctx.el('quantity').addEventListener('blur', function () {
			if (this.value !== '') {
				quantity.check();
			}
		});
		V2Form.bindPrice(price, recalc);
		// 日期最後綁定：綁定時會立刻檢查一次並呼叫 recalc，recalc 要用到上面的 quantity
		const dateField = V2Form.bindDate(date, PAGE.closedDays, recalc);

		/** 目前持有（0 股的持股不在 held 裡 → 當成新的一檔試算） */
		function currentHolding() {
			const held = PAGE.held[code.value];
			return held && held.shares > 0 ? held : null;
		}

		function showHoldingInfo() {
			const box = ctx.el('holding-info');
			const held = currentHolding();
			box.classList.toggle('d-none', !held);
			if (held) {
				const costCents = V2Form.plainToCents(held.total_cost);
				box.textContent = '目前持有 ' + V2Form.withCommas(held.shares) + ' 股，平均成本 '
					+ V2Form.formatCents(V2Form.avgCents(costCents, BigInt(held.shares))) + ' 元';
			}
		}

		/** 即時試算：數量、價格都合格才顯示 */
		function recalc() {
			const q = quantity.shares();
			const p = V2Form.parsePrice(price.value);
			const ready = !q.error && !p.error;
			ctx.el('calc-empty').classList.toggle('d-none', ready);
			ctx.el('calc-body').classList.toggle('d-none', !ready);
			if (!ready) {
				return;
			}
			const amountCents = q.shares * p.cents;
			const feeCents = V2Form.feeYuan(amountCents) * 100n;
			const costCents = amountCents + feeCents;
			ctx.el('calc-amount').textContent = V2Form.formatCents(amountCents);
			ctx.el('calc-fee').textContent = V2Form.formatCents(feeCents);
			ctx.el('calc-cost').textContent = V2Form.formatCents(costCents);

			const held = currentHolding();
			ctx.el('calc-after').classList.toggle('d-none', !held);
			if (held) {
				const newShares = BigInt(held.shares) + q.shares;
				ctx.el('calc-after-shares').textContent = V2Form.withCommas(newShares) + ' 股';
				ctx.el('calc-after-avg').textContent =
					V2Form.formatCents(V2Form.avgCents(V2Form.plainToCents(held.total_cost) + costCents, newShares)) + ' 元';
			}
		}

		showHoldingInfo();
		recalc();

		V2Form.bindSubmit(form, {
			validate: function () {
				// 每個欄位都檢查（不在第一個錯誤就停），一次把所有錯誤都顯示出來
				const results = [
					(function () { const m = V2Form.checkCode(code.value); V2Form.showError(code, m); return !m; })(),
					dateField.check(),
					quantity.check(),
					(function () { const m = V2Form.parsePrice(price.value).error; V2Form.showError(price, m); return !m; })(),
					// 股數 × 價格最多 100 億元（防多打 0；朋友回饋 3）。要放在數量、價格檢查之後
					V2Form.checkAmount(quantity, ctx.el('quantity'), price)
				];
				return results.every(Boolean);
			},
			confirm: dateField.confirmTradingDay,
			request: function () {
				return {
					method: 'POST',
					url: '/api/v2/holdings/' + encodeURIComponent(code.value) + '/trades',
					body: {
						side: 'BUY',
						trade_date: date.value,
						shares: Number(quantity.shares().shares), // 最大 99,999,999，在一般數字的精確範圍內
						price: V2Form.centsToPlain(V2Form.parsePrice(price.value).cents)
					}
				};
			},
			onSuccess: function (data) {
				return {
					message: '已記錄：買進 ' + data.holding.code + ' ' + (data.holding.name || '') + ' '
						+ V2Form.withCommas(data.trade.shares) + ' 股',
					redirect: ctx.redirect('/v2')
				};
			}
		});

		return PAGE.fixedCode ? ctx.el('quantity') : code; // 打開後游標放在這裡
	}

	// ===== 賣出 =====
	function sell(root, options) {
		const ctx = context(root, options);
		const PAGE = ctx.data;
		const code = PAGE.code;
		// 持有股數與成本（BigInt、以分計）：開頁時由伺服器放進來；賣超被擋時重新讀取
		let heldShares = BigInt(PAGE.held.shares);
		let heldCostCents = V2Form.plainToCents(PAGE.held.total_cost);

		const form = ctx.el('sell-form');
		const date = ctx.el('date');
		const price = ctx.el('price');
		ctx.bindCancel();
		date.value = V2Form.todayInTaipei();

		function renderHolding() {
			ctx.el('held-shares').textContent = V2Form.withCommas(heldShares);
			ctx.el('held-avg').textContent = heldShares > 0n
				? V2Form.formatCents(V2Form.avgCents(heldCostCents, heldShares)) : '—';
			// D-56：「資料時間 hh:mm:ss（台北時間）」拿掉（使用者決定）
		}

		const quantity = V2Form.bindQuantity({
			input: ctx.el('quantity'),
			unitName: 'unit',
			hint: ctx.el('quantity-hint'),
			// 打字當下就檢查「不能超過持有」
			extraCheck: function (shares) {
				return shares > heldShares
					? '不能超過持有的 ' + V2Form.withCommas(heldShares) + ' 股' + (heldShares >= 1000n ? '（' + V2Form.sharesToLotText(heldShares) + ' 張）' : '')
					: null;
			},
			onChange: recalc
		});
		ctx.el('quantity').addEventListener('blur', function () {
			if (this.value !== '') {
				quantity.check();
			}
		});
		V2Form.bindPrice(price, recalc);
		const dateField = V2Form.bindDate(date, PAGE.closedDays, recalc);

		function recalc() {
			const q = quantity.shares();
			const p = V2Form.parsePrice(price.value);
			const ready = !q.error && !p.error;
			ctx.el('calc-empty').classList.toggle('d-none', ready);
			ctx.el('calc-body').classList.toggle('d-none', !ready);
			if (!ready) {
				return;
			}
			const amount = q.shares * p.cents;
			const fee = V2Form.feeYuan(amount) * 100n;
			const taxExempt = V2Form.isBondEtfTaxExempt(code, date.value, PAGE.bondEtfExempt);
			const tax = V2Form.taxYuan(code, amount, date.value, PAGE.bondEtfExempt) * 100n;
			// 分攤成本：全部賣出 → 剩下的成本全部；否則 成本 × 賣出股數 ÷ 持有股數，四捨五入到分（和後端相同）
			const cost = q.shares === heldShares
				? heldCostCents
				: (heldCostCents * q.shares * 2n + heldShares) / (heldShares * 2n);
			const pnl = amount - fee - tax - cost;
			ctx.el('calc-amount').textContent = V2Form.formatCents(amount);
			ctx.el('calc-fee').textContent = V2Form.formatCents(fee);
			// 證交稅率寫在算式下方的說明（D-56，和手續費相同），算式那一行只留「− 證交稅」
			ctx.el('calc-tax-rate').textContent = taxExempt ? '債券 ETF 停徵' : code.startsWith('00') ? 'ETF 0.1%' : '0.3%';
			ctx.el('calc-tax').textContent = V2Form.formatCents(tax);
			ctx.el('calc-cost').textContent = V2Form.formatCents(cost);
			const pnlCell = ctx.el('calc-pnl');
			pnlCell.textContent = (pnl > 0n ? '+' : '') + V2Form.formatCents(pnl);
			pnlCell.className = 'v2-num ' + (pnl > 0n ? 'text-profit' : pnl < 0n ? 'text-loss' : '');
			ctx.el('calc-left').textContent = V2Form.withCommas(heldShares - q.shares) + ' 股';
		}

		ctx.el('sell-all').addEventListener('click', function (event) {
			event.preventDefault();
			if (ctx.el('quantity').readOnly) {
				return; // 送出中（輸入框是唯讀）不能改
			}
			quantity.setShares(heldShares); // 依目前單位填入（股：600；張：0.6）
			price.focus();
		});

		/** 重新讀取持有股數（另一個裝置可能已經賣掉一部分）：GET /api/v2/holdings/{code} */
		function reloadHolding() {
			fetch('/api/v2/holdings/' + encodeURIComponent(code), { headers: { 'Accept': 'application/json' } })
				.then(function (response) { return response.ok ? response.json() : null; })
				.then(function (holding) {
					if (!holding || holding.shares === null) {
						return; // 持股已刪除或資料異常：錯誤訊息已說明
					}
					heldShares = BigInt(holding.shares);
					heldCostCents = V2Form.plainToCents(holding.total_cost);
					renderHolding();
					quantity.check();
					recalc();
				})
				.catch(function () {
					// 讀不到就維持原本的數字；送出時伺服器仍會再檢查
				});
		}

		renderHolding();
		V2Form.bindSubmit(form, {
			validate: function () {
				const results = [
					dateField.check(),
					quantity.check(),
					(function () { const m = V2Form.parsePrice(price.value).error; V2Form.showError(price, m); return !m; })(),
					// 股數 × 價格最多 100 億元（防多打 0；朋友回饋 3）。要放在數量、價格檢查之後
					V2Form.checkAmount(quantity, ctx.el('quantity'), price)
				];
				return results.every(Boolean);
			},
			confirm: dateField.confirmTradingDay,
			request: function () {
				return {
					method: 'POST',
					url: '/api/v2/holdings/' + encodeURIComponent(code) + '/trades',
					body: {
						side: 'SELL',
						trade_date: date.value,
						shares: Number(quantity.shares().shares),
						price: V2Form.centsToPlain(V2Form.parsePrice(price.value).cents)
					}
				};
			},
			onSuccess: function (data) {
				// 寫入後剛好被另一個裝置改成資料異常時，算不出的欄位是 null（極少見）→ 只說記錄了幾股
				const pnl = data.trade ? V2Form.plainToCents(data.trade.realized_pnl) : null;
				// 格式：動作 代號 名稱 股數（已實現損益放括號）：股數和金額分開，避免把「22 股」和「−24,761.31」看成一起
				return {
					message: '已記錄：賣出 ' + code + ' ' + (PAGE.name || '') + ' ' + V2Form.withCommas(quantity.shares().shares) + ' 股'
						+ (pnl === null ? '' : '（已實現損益 ' + (pnl > 0n ? '+' : '') + V2Form.formatCents(pnl) + ' 元）'),
					redirect: ctx.redirect('/v2')
				};
			},
			onError: function (result) {
				if (result.error && result.error.code === 'OVERSELL') {
					reloadHolding(); // 例如手機剛賣掉一部分：顯示最新持有股數
				}
			}
		});

		return ctx.el('quantity');
	}

	// ===== 修改一筆交易 =====
	function edit(root, options) {
		const ctx = context(root, options);
		const PAGE = ctx.data;
		const form = ctx.el('edit-form');
		const date = ctx.el('date');
		const price = ctx.el('price');
		ctx.bindCancel();

		const quantity = V2Form.bindQuantity({
			input: ctx.el('quantity'),
			unitName: 'unit',
			hint: ctx.el('quantity-hint'),
			onChange: recalc
		});
		ctx.el('quantity').addEventListener('blur', function () {
			if (this.value !== '') {
				quantity.check();
			}
		});
		V2Form.bindPrice(price, recalc);
		const dateField = V2Form.bindDate(date, PAGE.closedDays, recalc);
		quantity.setShares(BigInt(PAGE.shares)); // 原本的股數，依記住的單位顯示（張：0.3）

		function recalc() {
			const q = quantity.shares();
			const p = V2Form.parsePrice(price.value);
			const ready = !q.error && !p.error;
			ctx.el('calc-empty').classList.toggle('d-none', ready);
			ctx.el('calc-body').classList.toggle('d-none', !ready);
			if (!ready) {
				return;
			}
			const amount = q.shares * p.cents;
			ctx.el('calc-amount').textContent = V2Form.formatCents(amount);
			ctx.el('calc-fee').textContent = V2Form.formatCents(V2Form.feeYuan(amount) * 100n);
			const tax = ctx.el('calc-tax');
			if (tax) {
				tax.textContent = V2Form.formatCents(V2Form.taxYuan(PAGE.code, amount, date.value, PAGE.bondEtfExempt) * 100n);
			}
		}

		V2Form.bindSubmit(form, {
			validate: function () {
				const results = [
					dateField.check(),
					quantity.check(),
					(function () { const m = V2Form.parsePrice(price.value).error; V2Form.showError(price, m); return !m; })(),
					// 股數 × 價格最多 100 億元（防多打 0；朋友回饋 3）。要放在數量、價格檢查之後
					V2Form.checkAmount(quantity, ctx.el('quantity'), price)
				];
				return results.every(Boolean);
			},
			confirm: dateField.confirmTradingDay,
			request: function () {
				return {
					method: 'PUT',
					url: '/api/v2/trades/' + PAGE.tradeId,
					body: {
						trade_date: date.value,
						shares: Number(quantity.shares().shares),
						price: V2Form.centsToPlain(V2Form.parsePrice(price.value).cents),
						version: PAGE.version // 頁面打開時的版本號：另一個裝置改過就會被拒絕（409），不會覆蓋
					}
				};
			},
			onSuccess: function () {
				return {
					// 格式和買進、賣出相同：動作 代號 名稱 股數（日期放最後的括號）
					message: '已修改：' + (PAGE.side === 'BUY' ? '買進' : '賣出') + ' ' + PAGE.code + ' ' + (PAGE.name || '') + ' '
						+ V2Form.withCommas(quantity.shares().shares) + ' 股（' + date.value + '）',
					redirect: ctx.redirect('/v2/holdings/' + encodeURIComponent(PAGE.code))
				};
			}
		});

		return null; // 修改：不自動把游標放進欄位（避免手機一打開就跳出鍵盤蓋住原本的內容）
	}

	const FORMS = { buy: buy, sell: sell, edit: edit };

	/** 獨立頁面：啟動頁面上的表單，游標放在第一個要填的欄位 */
	function initPage() {
		const root = document.querySelector('[data-v2-form]');
		const focus = FORMS[root.getAttribute('data-v2-form')](root, { inDialog: false });
		if (focus) {
			focus.focus();
		}
	}

	// ===== 對話框（總覽、明細頁有 #v2-form-dialog 時才啟動） =====
	function bindDialog() {
		const dialogEl = document.getElementById('v2-form-dialog');
		if (!dialogEl || !window.bootstrap || !window.bootstrap.Modal) {
			return; // 沒有對話框（或 Bootstrap 沒載入）：連結照常換頁，功能不受影響
		}
		const body = document.getElementById('v2-form-dialog-body');
		const modal = window.bootstrap.Modal.getOrCreateInstance(dialogEl);
		let focusTarget = null;
		let loading = false;

		// 打開後才把游標放進欄位（對話框動畫結束前 focus 會被 Bootstrap 移回對話框本身）
		dialogEl.addEventListener('shown.bs.modal', function () {
			if (focusTarget) {
				focusTarget.focus();
			}
		});
		// 關閉後清空：下次打開是全新的表單（不會留著上次填一半的內容、送出中的狀態）
		dialogEl.addEventListener('hidden.bs.modal', function () {
			body.replaceChildren();
			focusTarget = null;
		});

		document.addEventListener('click', function (event) {
			const link = event.target.closest('a[data-v2-dialog]');
			// 按著 Ctrl／Cmd／中鍵：使用者想開新分頁，照瀏覽器原本的行為
			if (!link || event.ctrlKey || event.metaKey || event.shiftKey || event.button !== 0) {
				return;
			}
			event.preventDefault();
			if (loading) {
				return; // 連點兩下：只開一次
			}
			loading = true;
			const href = link.getAttribute('href');
			fetch(href + (href.indexOf('?') >= 0 ? '&' : '?') + 'dialog=1', { headers: { 'Accept': 'text/html' } })
				.then(function (response) {
					// 伺服器把我們導回總覽（例如這檔已全部賣出、不能賣）：改用一般換頁，讓錯誤訊息照常顯示
					if (response.redirected || !response.ok) {
						throw new Error('fallback');
					}
					return response.text();
				})
				.then(function (html) {
					body.innerHTML = html; // 自己伺服器產生的表單片段（Thymeleaf 已跳脫使用者資料）
					const root = body.querySelector('[data-v2-form]');
					focusTarget = FORMS[root.getAttribute('data-v2-form')](root, { inDialog: true, close: function () { modal.hide(); } });
					modal.show();
				})
				.catch(function () {
					window.location.href = href; // 拿不到片段：退回原本的換頁方式
				})
				.finally(function () {
					loading = false;
				});
		});
	}

	window.V2Forms = { buy: buy, sell: sell, edit: edit, initPage: initPage };
	bindDialog();
})();
