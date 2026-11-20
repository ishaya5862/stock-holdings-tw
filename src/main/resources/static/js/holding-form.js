/*
 * 持股表單（新增／修改）的前端行為：
 * 1. 股票代號：輸入時即時「去空白、全形轉半形、轉大寫」並檢查格式（不用等按下送出）。
 * 2. 防止重複送出：按下送出後，按鈕變「送出中…」並停用、填寫區蓋上遮罩，直到跳轉到下一頁都不能再送。
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
	});
})();
