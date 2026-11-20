/*
 * 持股表單（新增／修改）的前端行為：
 * 1. 股票代號：輸入時即時「去空白、全形轉半形、轉大寫」並檢查格式（不用等按下送出）。
 * 2. 防止重複送出：按下送出後，按鈕變「送出中…」並停用、填寫區蓋上遮罩，直到跳轉到下一頁都不能再送。
 * 3. 預估買進成本：依張數、買進價即時試算「買進金額＋手續費」（只顯示，不送出）。
 *
 * 前端檢查只是提示，真正的把關在後端（HoldingForm、StockCodes）；規則取自頁面上的 data-pattern（後端 StockCodes.PATTERN）。
 *
 * 注意的陷阱：
 * - 在輸入框按 Enter 也會送出 → 用 submitting 旗標擋第二次送出（不能只停用按鈕）。
 * - 停用（disabled）的輸入框「值不會被送出」→ 送出中改用 readOnly（唯讀，值照常送出）＋遮罩，不用 disabled。
 * - 按瀏覽器「上一頁」回來時，瀏覽器可能原樣還原離開前的畫面（遮罩還在、按鈕停用）→ pageshow 時解除。
 * - 中文輸入法選字中（composition）改動輸入框內容會打斷選字 → 選字結束（compositionend）後才轉換。
 */
(function () {
	const form = document.querySelector('form[data-holding-form]');
	if (!form) {
		return;
	}
	const input = document.getElementById('code');
	const feedback = document.getElementById('code-feedback');
	const submitButton = form.querySelector('button[type="submit"]');
	const overlay = document.getElementById('submit-overlay');
	const originalButtonHtml = submitButton ? submitButton.innerHTML : '';
	// 只有新增頁有代號輸入框（修改頁的代號只顯示、不能改），沒有輸入框就不檢查代號
	const checkCode = input !== null && feedback !== null;

	let submitting = false;
	let composing = false;

	// ===== 1. 股票代號：統一寫法＋即時檢查 =====

	/** 與後端 StockCodes.normalize 相同：去掉所有空白、全形數字與英文轉半形、轉大寫 */
	function normalize(value) {
		return value
			.replace(/[\s　 ]/g, '')
			.replace(/[０-９Ａ-Ｚａ-ｚ]/g, function (ch) {
				return String.fromCharCode(ch.charCodeAt(0) - 0xFEE0); // 全形與半形相差 0xFEE0
			})
			.toUpperCase();
	}

	if (checkCode) {
		const fullPattern = new RegExp(input.dataset.pattern); // 4～6 位數字 + 可選 1 個英文字母
		const message = input.dataset.message;
		// 「打到一半」還可能是對的：0～6 位數字，或 4～6 位數字後面接 1 個英文字母
		const typingPattern = /^[0-9]{0,6}$|^[0-9]{4,6}[A-Z]$/;

		function showError(text) {
			feedback.textContent = text;
			input.classList.add('is-invalid');
		}

		function clearError() {
			feedback.textContent = '';
			input.classList.remove('is-invalid');
		}

		/** 把輸入框內容換成統一後的寫法（游標盡量留在原本的相對位置） */
		function applyNormalize() {
			const normalized = normalize(input.value);
			if (normalized !== input.value) {
				const removed = input.value.length - normalized.length;
				const caret = Math.max(0, (input.selectionStart || 0) - removed);
				input.value = normalized;
				input.setSelectionRange(caret, caret);
			}
		}

		/** 輸入中：不可能正確的內容立刻顯示錯誤；還沒打完（例如「23」）不顯示 */
		function checkWhileTyping() {
			const value = input.value;
			if (!typingPattern.test(value)) {
				showError(message);
			} else if (value === '' || fullPattern.test(value)) {
				clearError();
			}
		}

		/** 完整檢查（離開欄位、送出時）：空白或格式不完整都算錯 */
		window.checkHoldingCode = function () {
			applyNormalize();
			const value = input.value;
			if (value === '') {
				showError('請輸入股票代號');
				return false;
			}
			if (!fullPattern.test(value)) {
				showError(message);
				return false;
			}
			clearError();
			return true;
		};

		input.addEventListener('compositionstart', function () {
			composing = true;
		});
		input.addEventListener('compositionend', function () {
			composing = false;
			applyNormalize();
			checkWhileTyping();
		});
		input.addEventListener('input', function () {
			if (composing) {
				return; // 選字中先不動，等 compositionend
			}
			applyNormalize();
			checkWhileTyping();
		});
		input.addEventListener('blur', window.checkHoldingCode);
	}

	// ===== 2. 防止重複送出 =====

	function lockForm() {
		submitting = true;
		form.querySelectorAll('input').forEach(function (field) {
			field.readOnly = true; // 唯讀：不能再改，但值照常送出（disabled 的值不會送出）
		});
		if (overlay) {
			overlay.classList.remove('d-none');
		}
		if (submitButton) {
			// 等瀏覽器開始送出後才停用按鈕（在 submit 事件當下停用，少數瀏覽器會取消送出）
			setTimeout(function () {
				submitButton.disabled = true;
				submitButton.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>送出中…';
			}, 0);
		}
	}

	function unlockForm() {
		submitting = false;
		form.querySelectorAll('input').forEach(function (field) {
			field.readOnly = false;
		});
		if (overlay) {
			overlay.classList.add('d-none');
		}
		if (submitButton) {
			submitButton.disabled = false;
			submitButton.innerHTML = originalButtonHtml;
		}
	}

	form.addEventListener('submit', function (event) {
		if (submitting) {
			event.preventDefault(); // 已經在送出中（例如連按 Enter、連點按鈕）→ 擋下
			return;
		}
		if (checkCode && !window.checkHoldingCode()) {
			event.preventDefault(); // 格式不對：不送出，停在代號欄（後端仍會再檢查）
			input.focus();
			return;
		}
		lockForm();
	});

	// 從「上一頁」回到這一頁時，瀏覽器可能還原遮罩和停用狀態 → 解除，讓使用者可以再操作
	window.addEventListener('pageshow', function (event) {
		if (event.persisted) {
			unlockForm();
		}
		updateCostPreview(); // 瀏覽器還原欄位內容後，重新計算一次
	});

	// ===== 3. 預估買進成本（只在前端顯示，不送出、不存檔） =====
	//
	// 買進金額 = 張數 × 1,000 × 買進價；手續費 = 買進金額 × 費率，無條件捨去到元，最低 20 元。
	// 費率、最低手續費從頁面的 data- 屬性讀取（後端 ProfitCalculator 的值），前端不自己寫死。
	//
	// 為什麼用 BigInt（大整數）而不是一般數字：
	// JavaScript 一般數字是浮點數，有誤差（0.1 + 0.2 = 0.30000000000000004），而且超過 2^53 就不精確。
	// 所以先把價格換成「分」（整數），全部用 BigInt 整數運算，和後端用 BigDecimal 的理由相同。

	const costPreview = document.getElementById('cost-preview');
	const costSummary = document.getElementById('cost-preview-summary'); // 收合時那一行（標題後面的金額）
	const costResult = document.getElementById('cost-preview-result');   // 點開後的明細
	const lotsInput = document.getElementById('lots');
	const priceInput = document.getElementById('buyPrice');
	const EMPTY_TEXT = '：填寫張數與買進價後自動計算';
	const SHARES_PER_LOT = 1000n;

	/** 把小數字串（例 "0.001425"）拆成「分子 / 分母」兩個 BigInt：1425n / 1000000n */
	function parseDecimal(text) {
		const parts = String(text).split('.');
		const fraction = parts[1] || '';
		return {
			numerator: BigInt(parts[0] + fraction),
			denominator: 10n ** BigInt(fraction.length)
		};
	}

	/** 千分位：1001425n → "1,001,425" */
	function formatMoney(value) {
		return value.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
	}

	/** 張數：1～999 的整數，不合格回傳 null */
	function readLots() {
		const text = lotsInput.value.trim();
		if (!/^[0-9]{1,3}$/.test(text)) {
			return null;
		}
		const lots = BigInt(text);
		return lots >= 1n && lots <= 999n ? lots : null;
	}

	/** 買進價換成「分」：大於 0、最多 8 位整數、2 位小數，不合格回傳 null */
	function readPriceCents() {
		const match = /^([0-9]{1,8})(?:\.([0-9]{1,2}))?$/.exec(priceInput.value.trim());
		if (!match) {
			return null;
		}
		const cents = BigInt(match[1]) * 100n + BigInt((match[2] || '').padEnd(2, '0'));
		return cents > 0n ? cents : null;
	}

	function updateCostPreview() {
		if (!costPreview || !costSummary || !costResult || !lotsInput || !priceInput) {
			return;
		}
		const lots = readLots();
		const priceCents = readPriceCents();
		if (lots === null || priceCents === null) {
			// 還沒填好或格式不對：不顯示可能誤導的數字；明細清空（CSS 會把空的明細隱藏，點開只剩手續費規則）
			costSummary.textContent = EMPTY_TEXT;
			costResult.textContent = '';
			return;
		}
		const rate = parseDecimal(costPreview.dataset.feeRate);
		const minFee = BigInt(costPreview.dataset.minFee);

		// 張數 × 1,000 股 × 價格（分）÷ 100 = 元；1,000 股一定能被 100 整除，所以買進金額一定是整數元
		const amount = lots * SHARES_PER_LOT * priceCents / 100n;
		// BigInt 的除法會自動捨去小數（金額都是正數，等於無條件捨去）
		let fee = amount * rate.numerator / rate.denominator;
		if (fee < minFee) {
			fee = minFee; // 先捨去、再跟最低 20 元比（與後端相同）
		}
		const total = formatMoney(amount + fee) + ' 元';
		costSummary.textContent = ' ' + total + '（看明細）';
		costResult.textContent = '買進金額 ' + formatMoney(amount) + ' ＋ 手續費 ' + formatMoney(fee) + ' ＝ ' + total;
	}

	if (lotsInput && priceInput) {
		lotsInput.addEventListener('input', updateCostPreview);
		priceInput.addEventListener('input', updateCostPreview);
		updateCostPreview(); // 修改頁、驗證失敗重新顯示時，欄位已經有值 → 一打開就算好
	}
})();
