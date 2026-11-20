/*
 * 持股表單的前端行為：
 * 1. 股票代號「輸入時即時檢查格式」（不用等按下新增）。
 * 2. 送出後把按鈕變成「查詢中…」並停用，避免等待官方查詢時重複按。
 *
 * 注意：前端檢查只是讓使用者早點看到錯誤的「提示」，真正的把關在後端（HoldingForm 的 @Pattern）。
 * 前端的檢查可以被關掉或繞過（例如直接送請求），所以後端一定要再檢查一次。
 * 規則取自頁面上的 data-pattern（後端 StockCodes.PATTERN），前後端用同一份規則。
 */
(function () {
	const input = document.getElementById('code');
	const feedback = document.getElementById('code-feedback');
	const form = input ? input.form : null;
	if (!form) {
		return;
	}
	const submitButton = form.querySelector('button[type="submit"]');
	// 修改頁的代號是 readonly，不能改，不需要檢查格式
	const checkCode = !input.readOnly && feedback !== null;

	const fullPattern = new RegExp(input.dataset.pattern); // 完整格式：4～6 位數字 + 可選 1 個英文字母
	const message = input.dataset.message;
	// 「打到一半」還可能是對的：0～6 位數字，或 4～6 位數字後面接 1 個英文字母
	const typingPattern = /^[0-9]{0,6}$|^[0-9]{4,6}[A-Za-z]$/;

	function showError(text) {
		feedback.textContent = text;
		input.classList.add('is-invalid');
	}

	function clearError() {
		feedback.textContent = '';
		input.classList.remove('is-invalid');
	}

	/** 完整檢查（離開欄位、送出時）：空白或格式不完整都算錯 */
	function checkComplete() {
		const value = input.value.trim();
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
	}

	if (checkCode) {
		// 輸入時：英文自動轉大寫，並即時檢查
		input.addEventListener('input', function () {
			const upper = input.value.toUpperCase();
			if (upper !== input.value) {
				const caret = input.selectionStart;
				input.value = upper;
				input.setSelectionRange(caret, caret);
			}
			const value = input.value.trim();
			if (!typingPattern.test(value)) {
				// 出現「不可能是正確代號」的內容（例如中文、英文在中間、超過 6 位數字）→ 立刻顯示錯誤
				showError(message);
			} else if (value === '' || fullPattern.test(value)) {
				// 已經是完整格式（或清空了）→ 清掉錯誤
				clearError();
			}
			// 其他情況（例如只打了「23」，還沒打完）：不新增錯誤，避免一邊打一邊被紅字干擾
		});

		// 離開欄位時：做完整檢查（例如只打了 3 位數就離開 → 顯示格式錯誤）
		input.addEventListener('blur', checkComplete);
	}

	// 送出時：格式不對就不送出、停在錯誤的欄位（省一次往返；後端仍會再檢查）；
	// 檢查通過才把按鈕變成「查詢中…」並停用
	form.addEventListener('submit', function (event) {
		if (checkCode && !checkComplete()) {
			event.preventDefault();
			input.focus();
			return;
		}
		if (submitButton) {
			// 用 setTimeout：先讓瀏覽器把表單送出，再停用按鈕（直接停用可能讓表單送不出去）
			setTimeout(function () {
				submitButton.disabled = true;
				submitButton.textContent = '查詢中…';
			}, 0);
		}
	});
})();
