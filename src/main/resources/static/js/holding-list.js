/*
 * 持股清單頁：刪除按鈕防止重複送出。
 *
 * 為什麼需要：連點兩次「刪除」，第一次已經刪掉，第二次會變成「找不到這筆持股」，讓人誤會刪除失敗。
 * 做法：按下確認後，所有刪除按鈕都停用，被按的那顆變成「刪除中…」，直到跳轉回清單。
 * 從「上一頁」回到這一頁時（瀏覽器可能還原停用狀態）會解除。
 */
(function () {
	const forms = document.querySelectorAll('form[data-delete-form]');
	if (forms.length === 0) {
		return;
	}
	let submitting = false;

	forms.forEach(function (form) {
		form.addEventListener('submit', function (event) {
			if (submitting) {
				event.preventDefault();
				return;
			}
			if (!window.confirm('確定要刪除 ' + form.dataset.code + ' 嗎？')) {
				event.preventDefault();
				return;
			}
			submitting = true;
			const clicked = form.querySelector('button[type="submit"]');
			setTimeout(function () {
				document.querySelectorAll('form[data-delete-form] button[type="submit"]').forEach(function (button) {
					button.disabled = true;
				});
				clicked.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>刪除中…';
			}, 0);
		});
	});

	window.addEventListener('pageshow', function (event) {
		if (event.persisted) {
			submitting = false;
			document.querySelectorAll('form[data-delete-form] button[type="submit"]').forEach(function (button) {
				button.disabled = false;
				button.innerHTML = button.dataset.originalHtml || button.innerHTML;
			});
		}
	});

	// 記下按鈕原本的內容，從上一頁回來時還原
	document.querySelectorAll('form[data-delete-form] button[type="submit"]').forEach(function (button) {
		button.dataset.originalHtml = button.innerHTML;
	});
})();
