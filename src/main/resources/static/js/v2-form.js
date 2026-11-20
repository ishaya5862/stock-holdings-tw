/*
 * 持股記帳 2.0：買進、賣出、修改頁共用的前端行為（第 6 題 C9；由 2026-11-20 預覽版 qiao/preview-v2/v2-form.js 正式寫入）。
 *
 * 沿用第一版 holding-form.js 的防呆：
 * 1. 輸入時即時去空白、全形轉半形（代號再轉大寫）；中文輸入法選字中不打斷（compositionend 後才轉換）。
 * 2. 防止重複送出：submitting 旗標（擋連按 Enter、連點）、輸入框改唯讀、遮罩、按鈕轉圈「送出中…」。
 * 3. 按瀏覽器「上一頁」回來時（pageshow），解除鎖定。
 *
 * 2.0 新增：
 * 4. 股數可用「股」或「張」填寫（張可到小數三位，1.5 張 = 1,500 股）；送給後端一律是「股數」整數。
 *    換算用字串＋BigInt，不用浮點數（0.1 + 0.2 ≠ 0.3 的問題），1.001 張一定剛好 1,001 股。
 * 5. 打字過程中就檢查：不可能正確的內容（小數位太多、非數字、超過上限、賣超）立刻提示；
 *    「還沒打完」的內容（例如價格打到「10.」、張數打到「1.」）不提示，避免一直閃紅字。
 * 6. Idempotency-Key（C7）：每次「打開這一頁」產生一個；失敗或逾時後重送用同一個，伺服器只會記一筆。成功後換新的。
 * 7. 逾時：10 秒沒回應 → 告訴使用者「結果不確定，可以直接再送一次，不會重複記錄」。
 * 8. 「今天」一律用台北時間（Intl 指定 Asia/Taipei），不用電腦的時區（出國、電腦時區設錯也不會判錯）。
 * 9. 金額試算用 BigInt、以「分」計算：最大金額約 10^16 元，超過 JS 一般數字能精確表示的範圍（約 9×10^15）。
 * 10. 週末、休市日只「提醒」，不擋（題目 1 a＋d、C3-7）：休市日資料由伺服器放進頁面（C8-5 a）。
 *
 * 前端檢查只是提示，真正的把關在後端（同時操作、賣超都以伺服器重算為準）。
 * 伺服器回傳的文字一律用 textContent 顯示（不當成 HTML），避免被插入程式碼。
 */
window.V2Form = (function () {
	'use strict';

	// ===== 共用規則（和後端 TradeService 相同：單筆 1～99,999,999 股、價格 0.01～99,999,999.99、日期 1990-01-01 起） =====
	const MAX_SHARES = 99999999n;
	const SHARES_PER_LOT = 1000n;
	const MAX_PRICE_CENTS = 9999999999n; // 99,999,999.99 元
	const EARLIEST_DATE = '1990-01-01';
	const CODE_PATTERN = /^[0-9]{4,6}[A-Z]?$/;
	const TIMEOUT_MS = 10000; // 和 App 2.0 的重送時間一致
	const MAX_SHARES_MESSAGE = '單筆最多 99,999,999 股；數量更大請分成多筆記錄';
	const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六'];

	/** 全形數字、英文、小數點、逗號轉半形，去掉空白（和第一版相同的規則，多了小數點與逗號） */
	function toHalfWidth(value) {
		return value
			.replace(/[\s　 ]/g, '')
			.replace(/[０-９Ａ-Ｚａ-ｚ．，]/g, function (ch) {
				return String.fromCharCode(ch.charCodeAt(0) - 0xFEE0);
			});
	}

	/** 加上千分位逗號（BigInt 或數字都可以） */
	function withCommas(value) {
		return value.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
	}

	/** 台北時間的今天（YYYY-MM-DD）：en-CA 的日期格式剛好是 YYYY-MM-DD */
	function todayInTaipei() {
		return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Taipei' }).format(new Date());
	}

	// ===== 股數（股／張） =====

	/**
	 * 打字中的檢查：只抓「不可能正確」的內容。回傳錯誤訊息或 null。
	 * @param text 已轉半形、去掉逗號的內容
	 * @param unit 'share' 或 'lot'
	 */
	function quantityTypingError(text, unit) {
		if (text === '') {
			return null;
		}
		if (unit === 'share') {
			if (/^[0-9]+\.[0-9]*$/.test(text)) {
				return '股數必須是整數（例如 300、1500）；要填 1.5 張請切換成「張」';
			}
			if (!/^[0-9]+$/.test(text)) {
				return '股數只能輸入數字（例如 300、1500）';
			}
		} else {
			if (!/^[0-9]*\.?[0-9]*$/.test(text)) {
				return '張數只能輸入數字（例如 2、1.5）';
			}
			if (/\.[0-9]{4,}$/.test(text)) {
				return '張數最多三位小數（1 張 = 1,000 股，例如 1.234 張 = 1,234 股）';
			}
		}
		const shares = toShares(text, unit);
		if (shares !== null && shares > MAX_SHARES) {
			return MAX_SHARES_MESSAGE;
		}
		return null;
	}

	/** 「股」或「張」轉成股數（BigInt）；格式不完整時回傳 null。不用浮點數。 */
	function toShares(text, unit) {
		if (unit === 'share') {
			return /^[0-9]+$/.test(text) ? BigInt(text) : null;
		}
		const match = /^([0-9]*)(?:\.([0-9]{0,3}))?$/.exec(text);
		if (!match || (match[1] === '' && !match[2])) {
			return null;
		}
		const whole = BigInt(match[1] || '0');
		const fraction = BigInt((match[2] || '').padEnd(3, '0')); // 「.5」→「500」股
		return whole * SHARES_PER_LOT + fraction;
	}

	/** 股數轉成「張」的文字（1500 → "1.5"、1000 → "1"） */
	function sharesToLotText(shares) {
		const whole = shares / SHARES_PER_LOT;
		const rest = shares % SHARES_PER_LOT;
		return rest === 0n
			? whole.toString()
			: whole.toString() + '.' + rest.toString().padStart(3, '0').replace(/0+$/, '');
	}

	/** 「= 1,500 股（1 張又 500 股）」的說明文字 */
	function describeShares(shares) {
		const lots = shares / SHARES_PER_LOT;
		const odd = shares % SHARES_PER_LOT;
		let detail;
		if (lots === 0n) {
			detail = '零股';
		} else if (odd === 0n) {
			detail = withCommas(lots) + ' 張';
		} else {
			detail = withCommas(lots) + ' 張又 ' + odd + ' 股';
		}
		return '= ' + withCommas(shares) + ' 股（' + detail + '）';
	}

	/** 完整檢查（離開欄位、送出時）：回傳 { shares（BigInt）, error } */
	function parseQuantity(raw, unit) {
		const text = toHalfWidth(raw).replace(/,/g, '');
		if (text === '') {
			return { error: unit === 'share' ? '請輸入股數' : '請輸入張數' };
		}
		const typing = quantityTypingError(text, unit);
		if (typing) {
			return { error: typing };
		}
		const shares = toShares(text, unit);
		if (shares === null) {
			return { error: '張數格式不完整（例如 2、1.5）' };
		}
		if (shares < 1n) {
			return { error: '最少 1 股' };
		}
		return { shares: shares };
	}

	// ===== 價格 =====

	function priceTypingError(text) {
		if (text === '') {
			return null;
		}
		if (!/^[0-9]*\.?[0-9]*$/.test(text)) {
			return '價格只能輸入數字（例如 600、28.55）';
		}
		if (/\.[0-9]{3,}$/.test(text)) {
			return '價格最多兩位小數（例如 28.55）';
		}
		const cents = toCents(text);
		if (cents !== null && cents > MAX_PRICE_CENTS) {
			return '價格最大 99,999,999.99';
		}
		return null;
	}

	function toCents(text) {
		const match = /^([0-9]*)(?:\.([0-9]{0,2}))?$/.exec(text);
		if (!match || (match[1] === '' && !match[2])) {
			return null;
		}
		return BigInt(match[1] || '0') * 100n + BigInt((match[2] || '').padEnd(2, '0'));
	}

	/** 完整檢查：回傳 { cents（BigInt）, error } */
	function parsePrice(raw) {
		const text = toHalfWidth(raw).replace(/,/g, '');
		if (text === '') {
			return { error: '請輸入價格' };
		}
		const typing = priceTypingError(text);
		if (typing) {
			return { error: typing };
		}
		const cents = toCents(text);
		if (cents === null) {
			return { error: '價格格式不完整（例如 600、28.55）' };
		}
		if (cents <= 0n) {
			return { error: '價格必須大於 0' };
		}
		return { cents: cents };
	}

	/** 單筆成交金額上限 100 億元（以分計；和後端 TradeService.MAX_AMOUNT 相同，朋友回饋 3） */
	const MAX_AMOUNT_CENTS = 1000000000000n;

	/** 股數 × 價格超過 100 億元 → 錯誤訊息（防多打 0）；沒超過 → null */
	function amountError(shares, cents) {
		return shares * cents > MAX_AMOUNT_CENTS
			? '單筆成交金額（股數 × 價格）最多 100 億元，請確認股數與價格是否多打了 0'
			: null;
	}

	/** 分 → API 用的價格字串（60050 → "600.50"；API 的 price 是兩位小數字串，C6-6 a） */
	function centsToPlain(cents) {
		return (cents / 100n).toString() + '.' + (cents % 100n).toString().padStart(2, '0');
	}

	/** API 的金額字串 → 分（"368022.75" → 36802275n）；null → null */
	function plainToCents(text) {
		if (text === null || text === undefined) {
			return null;
		}
		const negative = text.startsWith('-');
		const cents = toCents(negative ? text.slice(1) : text);
		return negative ? -cents : cents;
	}

	/**
	 * 送出前檢查金額上限，錯誤顯示在數量欄（股數、價格本身有錯時不重複提示）。
	 * @return 是否合格
	 */
	function checkAmount(quantityField, quantityInput, priceInput) {
		const q = quantityField.shares();
		const p = parsePrice(priceInput.value);
		if (q.error || p.error) {
			return true; // 由各自的欄位檢查顯示錯誤
		}
		const message = amountError(q.shares, p.cents);
		if (message) {
			showError(quantityInput, message);
		}
		return !message;
	}

	// ===== 日期、代號 =====

	/** 日期：必填、1990-01-01 起、不能晚於台北時間的今天（硬性規則，和後端相同） */
	function checkDate(value) {
		if (!value) {
			return '請選擇日期';
		}
		if (value < EARLIEST_DATE) {
			return '日期不能早於 ' + EARLIEST_DATE;
		}
		if (value > todayInTaipei()) {
			return '日期不能晚於今天（台北時間 ' + todayInTaipei() + '）';
		}
		return null;
	}

	/**
	 * 這天可能不是交易日的原因（只提醒、不擋）；看起來是交易日回傳 null。
	 * @param value      YYYY-MM-DD
	 * @param closedDays 伺服器放進頁面的已知休市日：{ "2025": ["2025-01-27", ...], ... }
	 * @return { message, noData }：noData = 那一年沒有休市日資料，只檢查了週末
	 */
	function nonTradingDayWarning(value, closedDays) {
		if (!value || checkDate(value)) {
			return null; // 日期本身有錯時，只顯示錯誤
		}
		const parts = value.split('-').map(Number);
		// 用 UTC 算星期幾：不受電腦時區影響（日期字串本身就是台北的日期）
		const weekday = new Date(Date.UTC(parts[0], parts[1] - 1, parts[2])).getUTCDay();
		if (weekday === 0 || weekday === 6) {
			return { message: '這天是星期' + WEEKDAYS[weekday] + '，台股沒有開盤。確定日期正確嗎？', noData: false };
		}
		const year = String(parts[0]);
		if (!closedDays || !closedDays[year]) {
			return { message: year + ' 年沒有休市日資料，只檢查了週末', noData: true };
		}
		if (closedDays[year].indexOf(value) >= 0) {
			return { message: '這天是休市日，台股沒有開盤。確定日期正確嗎？', noData: false };
		}
		return null;
	}

	/** 代號打字中：只抓不可能正確的（和第一版相同的「打到一半」規則） */
	function codeTypingError(value) {
		if (value === '' || /^[0-9]{0,6}$|^[0-9]{4,6}[A-Z]$/.test(value)) {
			return null;
		}
		return '股票代號格式不正確（4～6 位數字，可加 1 個英文字母，例如 2330、00679B）';
	}

	function checkCode(value) {
		if (value === '') {
			return '請輸入股票代號';
		}
		if (!CODE_PATTERN.test(value)) {
			return '股票代號格式不正確（4～6 位數字，可加 1 個英文字母，例如 2330、00679B）';
		}
		return null;
	}

	// ===== 金額試算（以「分」為單位的 BigInt；只是試算，實際數字以伺服器重算為準） =====

	/** 手續費（元）：成交金額 × 0.1425%，無條件捨去到元，最低 20 元 */
	function feeYuan(amountCents) {
		const fee = amountCents * 1425n / 100000000n; // 分 × 0.001425 ÷ 100 = 元；BigInt 除法本身就是無條件捨去
		return fee < 20n ? 20n : fee;
	}

	/**
	 * 債券 ETF（00＋3 碼＋B／C，例如 00679B）在這一天賣出是否停徵證交稅（第 7 題回報二；規則同後端 ProfitCalculator）。
	 * @param exempt 伺服器放進頁面的停徵期間 { from: '2017-01-01', until: '2026-12-31' }（期限在後端設定）
	 */
	function isBondEtfTaxExempt(code, sellDate, exempt) {
		return /^00\d{3}[BC]$/.test(code) && Boolean(exempt) && Boolean(sellDate)
			&& sellDate >= exempt.from && sellDate <= exempt.until; // YYYY-MM-DD 字串可以直接比大小
	}

	/**
	 * 證交稅（元）：一般股票 0.3%、ETF（00 開頭）0.1%，無條件捨去到元；債券 ETF 停徵期間 0 元。
	 * 只是試算，實際以伺服器重算為準。
	 */
	function taxYuan(code, amountCents, sellDate, exempt) {
		if (isBondEtfTaxExempt(code, sellDate, exempt)) {
			return 0n;
		}
		const perMille = code.startsWith('00') ? 1n : 3n;
		return amountCents * perMille / 100000n;
	}

	/** 平均成本四捨五入到分（只顯示）：(成本 × 2 + 股數) ÷ (股數 × 2) */
	function avgCents(costCents, shares) {
		return (costCents * 2n + shares) / (shares * 2n);
	}

	/** 分 → 「1,234.56」（負數用「−」） */
	function formatCents(cents) {
		const negative = cents < 0n;
		const abs = negative ? -cents : cents;
		return (negative ? '−' : '') + withCommas(abs / 100n) + '.' + (abs % 100n).toString().padStart(2, '0');
	}

	// ===== 欄位錯誤顯示 =====

	function showError(input, message) {
		input.classList.toggle('is-invalid', Boolean(message));
		const feedback = document.getElementById(input.id + '-feedback');
		if (feedback) {
			feedback.textContent = message || '';
		}
	}

	/**
	 * 共用的「輸入中」處理：轉半形 → 打字中檢查。中文輸入法選字中先不動（沿用第一版）。
	 * @param transform 轉換函式（代號會再轉大寫）
	 * @param typingCheck 回傳錯誤訊息或 null
	 * @param after 每次內容變動後要做的事（例如重新試算）
	 */
	function bindLiveInput(input, transform, typingCheck, after) {
		let composing = false;
		function handle() {
			const converted = transform(input.value);
			if (converted !== input.value) {
				input.value = converted;
			}
			showError(input, typingCheck(input.value));
			if (after) {
				after();
			}
		}
		input.addEventListener('compositionstart', function () { composing = true; });
		input.addEventListener('compositionend', function () { composing = false; handle(); });
		input.addEventListener('input', function () {
			if (!composing) {
				handle();
			}
		});
	}

	/**
	 * 股數欄位（含「股｜張」切換）。
	 * @param options.input    輸入框
	 * @param options.unitName 單位 radio 的 name
	 * @param options.hint     顯示「= 1,500 股」的元素
	 * @param options.extraCheck (shares) => 錯誤訊息或 null（賣出頁用來檢查「不能超過持有」）
	 * @param options.onChange 內容或單位變動後要做的事
	 * @return { unit(), shares() → { shares, error }, setShares(BigInt), check() }
	 */
	function bindQuantity(options) {
		const input = options.input;
		const radios = document.querySelectorAll('input[name="' + options.unitName + '"]');

		function unit() {
			return document.querySelector('input[name="' + options.unitName + '"]:checked').value;
		}

		function updateHint() {
			const text = input.value.replace(/,/g, '');
			const shares = quantityTypingError(text, unit()) ? null : toShares(text, unit());
			options.hint.textContent = shares !== null && shares > 0n
				? describeShares(shares)
				: (unit() === 'share' ? '零股直接填股數，或切換成「張」' : '可填小數，例如 1.5 張 = 1,500 股');
		}

		/** 打字中：格式問題＋額外檢查（例如賣超）都立刻提示 */
		function typingCheck(value) {
			const text = value.replace(/,/g, '');
			const error = quantityTypingError(text, unit());
			if (error) {
				return error;
			}
			const shares = toShares(text, unit());
			return shares !== null && shares > 0n && options.extraCheck ? options.extraCheck(shares) : null;
		}

		bindLiveInput(input, toHalfWidth, typingCheck, function () {
			updateHint();
			if (options.onChange) {
				options.onChange();
			}
		});

		// 切換單位：已經填好的合格數字自動換算（1500 股 ⇄ 1.5 張），不合格的保留原文字重新檢查
		let previousUnit = unit();
		radios.forEach(function (radio) {
			radio.addEventListener('change', function () {
				const text = input.value.replace(/,/g, '');
				const shares = quantityTypingError(text, previousUnit) ? null : toShares(text, previousUnit);
				if (shares !== null && text !== '') {
					input.value = unit() === 'lot' ? sharesToLotText(shares) : shares.toString();
				}
				previousUnit = unit();
				try {
					localStorage.setItem('v2-quantity-unit', unit()); // 記住使用者習慣的單位（只是方便，失敗不影響）
				} catch (e) {
					// 無痕模式等情況不能用 localStorage，忽略
				}
				showError(input, typingCheck(input.value));
				updateHint();
				if (options.onChange) {
					options.onChange();
				}
				input.focus();
			});
		});

		// 上次用的單位（修改頁一開始就有數字：先選單位再填數字，由呼叫的頁面處理）
		try {
			const saved = localStorage.getItem('v2-quantity-unit');
			const savedRadio = saved && document.querySelector('input[name="' + options.unitName + '"][value="' + saved + '"]');
			if (savedRadio) {
				savedRadio.checked = true;
				previousUnit = saved;
			}
		} catch (e) {
			// 忽略
		}
		updateHint();

		function shares() {
			const result = parseQuantity(input.value, unit());
			if (!result.error && options.extraCheck) {
				const extra = options.extraCheck(result.shares);
				if (extra) {
					return { error: extra };
				}
			}
			return result;
		}

		return {
			unit: unit,
			shares: shares,
			/** 填入股數（例如「全部賣出」、修改頁的原本股數），依目前單位顯示 */
			setShares: function (value) {
				input.value = unit() === 'lot' ? sharesToLotText(value) : value.toString();
				showError(input, typingCheck(input.value));
				updateHint();
				if (options.onChange) {
					options.onChange();
				}
			},
			/** 完整檢查並顯示錯誤；回傳是否合格 */
			check: function () {
				const result = shares();
				showError(input, result.error);
				return !result.error;
			}
		};
	}

	/** 價格欄位：打字中檢查＋離開時完整檢查 */
	function bindPrice(input, onChange) {
		bindLiveInput(input, toHalfWidth, function (value) {
			return priceTypingError(value.replace(/,/g, ''));
		}, onChange);
		input.addEventListener('blur', function () {
			if (input.value !== '') {
				showError(input, parsePrice(input.value).error);
			}
		});
	}

	/** 代號欄位：打字中轉半形＋大寫並檢查（沿用第一版） */
	function bindCode(input, onChange) {
		bindLiveInput(input, function (value) {
			return toHalfWidth(value).toUpperCase();
		}, codeTypingError, onChange);
	}

	/**
	 * 日期欄位：硬性錯誤（未來、1990 以前）顯示紅字；週末、休市日顯示黃色提醒（不擋）。
	 * @return check()：完整檢查並顯示，回傳是否合格
	 */
	function bindDate(input, closedDays, onChange) {
		const warningBox = document.getElementById(input.id + '-warning');
		function refresh() {
			showError(input, checkDate(input.value));
			const warning = nonTradingDayWarning(input.value, closedDays);
			if (warningBox) {
				warningBox.textContent = warning ? warning.message : '';
				warningBox.classList.toggle('text-warning-emphasis', Boolean(warning && !warning.noData));
				warningBox.classList.toggle('text-secondary', Boolean(warning && warning.noData));
			}
			if (onChange) {
				onChange();
			}
		}
		input.max = todayInTaipei(); // 日曆不能選未來（只是方便，真正的檢查在 checkDate 與後端）
		input.min = EARLIEST_DATE;
		input.addEventListener('change', refresh);
		input.addEventListener('input', refresh);
		refresh();
		return {
			check: function () {
				const error = checkDate(input.value);
				showError(input, error);
				return !error;
			},
			/** 送出前：週末、休市日請使用者確認一次（沒有資料的年份不問） */
			confirmTradingDay: function () {
				const warning = nonTradingDayWarning(input.value, closedDays);
				return !warning || warning.noData || window.confirm(input.value + '：' + warning.message);
			}
		};
	}

	// ===== 呼叫 API（Idempotency-Key＋10 秒逾時） =====

	function newKey() {
		// crypto.randomUUID 需要 https 或 localhost；不能用時改用時間＋亂數（只要不重複、符合 1～64 英數字 - 即可）
		return (window.crypto && crypto.randomUUID)
			? crypto.randomUUID()
			: Date.now() + '-' + Math.random().toString(16).slice(2);
	}

	/**
	 * 呼叫 /api/v2 的寫入 API。
	 * @return Promise<{ ok, status, data, error, uncertain }>：
	 *   ok        2xx 成功（data 是回應內容）
	 *   error     伺服器回的錯誤 {code, message, fields?, details?}
	 *   uncertain 逾時或連不上：不知道伺服器有沒有收到（可以用同一個 key 直接再送）
	 */
	function callApi(method, url, body, idempotencyKey) {
		const controller = window.AbortController ? new AbortController() : null;
		const timer = setTimeout(function () {
			if (controller) {
				controller.abort(); // 10 秒沒回應：停止等待（伺服器可能已經做完，所以結果「不確定」）
			}
		}, TIMEOUT_MS);
		const headers = { 'Idempotency-Key': idempotencyKey, 'Accept': 'application/json' };
		if (body) {
			headers['Content-Type'] = 'application/json';
		}
		return fetch(url, {
			method: method,
			headers: headers,
			body: body ? JSON.stringify(body) : undefined,
			signal: controller ? controller.signal : undefined
		}).then(function (response) {
			return response.json().catch(function () { return null; }).then(function (data) {
				if (response.ok) {
					return { ok: true, status: response.status, data: data };
				}
				const error = data && data.error ? data.error
					: { code: 'HTTP_' + response.status, message: '伺服器發生錯誤（' + response.status + '），請稍後再試' };
				return { ok: false, status: response.status, error: error };
			});
		}).catch(function () {
			return { ok: false, uncertain: true }; // 逾時（abort）或網路斷線
		}).finally(function () {
			clearTimeout(timer);
		});
	}

	/** 錯誤訊息的欄位名稱（API 的 fields）→ 頁面上的輸入框 id */
	const FIELD_INPUTS = { trade_date: 'date', shares: 'quantity', price: 'price', code: 'code' };

	/** 在提示區顯示訊息（純文字＋可選的連結清單），不使用 innerHTML */
	function showAlert(box, type, title, text, links) {
		box.className = 'alert alert-' + type;
		box.replaceChildren();
		if (title) {
			const strong = document.createElement('strong');
			strong.textContent = title;
			box.appendChild(strong);
		}
		if (text) {
			box.appendChild(document.createTextNode(text));
		}
		(links || []).forEach(function (link) {
			const div = document.createElement('div');
			div.className = 'mt-1';
			const a = document.createElement('a');
			a.href = link.href;
			a.textContent = link.text;
			a.className = 'alert-link';
			div.appendChild(a);
			box.appendChild(div);
		});
		box.scrollIntoView({ block: 'nearest' });
	}

	/** 賣超時，伺服器附上「日期是推估的買進」（朋友回饋 2-1 a）→ 連到修改那筆買進 */
	function estimatedBuyLinks(error) {
		const buys = error.details && error.details.estimated_buys;
		return (buys || []).map(function (buy) {
			return { href: '/v2/trades/' + buy.id + '/edit', text: '修改推估的買進日期（目前 ' + buy.trade_date + '）' };
		});
	}

	/**
	 * 一般的錯誤處理（各頁共用）：顯示訊息、標出欄位、依錯誤碼決定 Idempotency-Key 要不要換。
	 * @return true = 要換新的 key（下一次送出會被當成新的請求）
	 */
	function handleError(result, alertBox) {
		if (result.uncertain) {
			showAlert(alertBox, 'warning', '伺服器沒有回應，結果不確定。',
				'可以直接再按一次送出：系統會用同一個識別碼，不會重複記錄。');
			return false; // key 不換，重送才會被認出是同一筆
		}
		const error = result.error;
		switch (error.code) {
			case 'IDEMPOTENCY_KEY_IN_PROGRESS':
				showAlert(alertBox, 'warning', '上一次送出還在處理中。', '請稍候再按一次送出（不會重複記錄）。');
				return false;
			case 'IDEMPOTENCY_KEY_REUSED':
				// 逾時後改了內容再送：同一個 key 不同內容 → 伺服器拒絕（避免把第二筆當成重送而消失）
				showAlert(alertBox, 'warning', '上一次送出的結果不確定，而且這次內容不同。',
					'請先回持股總覽確認上一筆有沒有記錄；確認後再按送出，會當成新的一筆。');
				return true;
			case 'VALIDATION_ERROR':
				Object.keys(error.fields || {}).forEach(function (field) {
					const input = document.getElementById(FIELD_INPUTS[field]);
					if (input) {
						showError(input, error.fields[field]);
					}
				});
				showAlert(alertBox, 'danger', '', error.message);
				return false;
			case 'VERSION_CONFLICT':
				// 另一個裝置（例如手機）先改過或刪過 → 不覆蓋，請使用者看過最新內容再決定
				showAlert(alertBox, 'warning', '', error.message,
					[{ href: window.location.href, text: '重新載入最新內容' }]);
				return false;
			case 'TRADE_NOT_FOUND':
			case 'NOT_HELD':
				showAlert(alertBox, 'warning', '', error.message, [{ href: '/v2', text: '回持股總覽' }]);
				return false;
			default:
				showAlert(alertBox, 'danger', '', error.message, estimatedBuyLinks(error));
				return false; // 失敗的請求伺服器不會記住 key，沿用不影響
		}
	}

	// ===== 送出流程（防重複送出＋Idempotency-Key＋逾時） =====

	/**
	 * @param form 表單
	 * @param options.validate  檢查全部欄位，回傳 true 才送出
	 * @param options.confirm   （可選）送出前的確認，例如週末、休市日；回傳 false 就不送
	 * @param options.request   () => { method, url, body }
	 * @param options.onSuccess (data) => { message, redirect }：成功提示與要跳去的頁面
	 * @param options.onError   （可選）(result) => void：共用處理之後再做的事（例如賣超時重新讀取持有股數）
	 */
	function bindSubmit(form, options) {
		const button = form.querySelector('button[type="submit"]');
		const buttonHtml = button.innerHTML;
		const overlay = form.closest('.v2-section').querySelector('.v2-submit-overlay');
		const alertBox = document.getElementById('form-alert');
		let submitting = false;
		let idempotencyKey = newKey();

		function setLocked(locked) {
			submitting = locked;
			form.querySelectorAll('input').forEach(function (field) {
				if (field.type === 'radio') {
					field.disabled = locked; // 單位切換：radio 沒有唯讀，只能停用
				} else {
					// 原本就固定的欄位（例如從持股列點進來的代號）維持唯讀
					field.readOnly = locked || field.hasAttribute('data-always-readonly');
				}
			});
			overlay.classList.toggle('d-none', !locked);
			button.disabled = locked;
			button.innerHTML = locked
				? '<span class="spinner-border spinner-border-sm me-1"></span>送出中…'
				: buttonHtml;
		}

		form.addEventListener('submit', function (event) {
			event.preventDefault(); // 用 JS 送出（呼叫 API），不讓瀏覽器整頁送出
			if (submitting) {
				return; // 送出中再按 Enter／再點 → 不理會
			}
			alertBox.className = 'd-none';
			if (!options.validate()) {
				const firstInvalid = form.querySelector('.is-invalid');
				if (firstInvalid) {
					firstInvalid.focus();
				}
				return;
			}
			if (options.confirm && !options.confirm()) {
				return;
			}
			setLocked(true);
			const request = options.request();
			callApi(request.method, request.url, request.body, idempotencyKey).then(function (result) {
				if (result.ok) {
					idempotencyKey = newKey(); // 成功後換新的，下一筆才不會被當成重送
					const success = options.onSuccess(result.data);
					try {
						sessionStorage.setItem('v2-flash', success.message);
					} catch (e) {
						// 無痕模式等情況可能不能用 sessionStorage，只是少了提示訊息，不影響功能
					}
					window.location.href = success.redirect;
					return; // 跳頁前維持鎖定，避免再送一次
				}
				setLocked(false);
				if (handleError(result, alertBox)) {
					idempotencyKey = newKey();
				}
				if (options.onError) {
					options.onError(result);
				}
			});
		});

		// 按「上一頁」回到這一頁時，瀏覽器可能原樣還原「送出中」的畫面 → 解除鎖定、換新識別碼
		window.addEventListener('pageshow', function (event) {
			if (event.persisted) {
				setLocked(false);
				idempotencyKey = newKey();
			}
		});
	}

	return {
		parseQuantity: parseQuantity,
		quantityTypingError: quantityTypingError,
		toShares: toShares,
		sharesToLotText: sharesToLotText,
		describeShares: describeShares,
		parsePrice: parsePrice,
		priceTypingError: priceTypingError,
		amountError: amountError,
		checkAmount: checkAmount,
		centsToPlain: centsToPlain,
		plainToCents: plainToCents,
		checkDate: checkDate,
		nonTradingDayWarning: nonTradingDayWarning,
		checkCode: checkCode,
		codeTypingError: codeTypingError,
		todayInTaipei: todayInTaipei,
		feeYuan: feeYuan,
		taxYuan: taxYuan,
		isBondEtfTaxExempt: isBondEtfTaxExempt,
		avgCents: avgCents,
		formatCents: formatCents,
		withCommas: withCommas,
		showError: showError,
		showAlert: showAlert,
		bindQuantity: bindQuantity,
		bindPrice: bindPrice,
		bindCode: bindCode,
		bindDate: bindDate,
		bindSubmit: bindSubmit,
		callApi: callApi,
		handleError: handleError,
		newKey: newKey
	};
})();
