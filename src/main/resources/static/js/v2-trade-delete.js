/*
 * 持股記帳 2.0：明細頁的「刪除」一筆交易（第 6 題 C9；C8-4 a 在明細頁確認後刪除）。
 *
 * - 確認視窗說明要刪哪一筆；刪掉最後一筆時，提醒持股會一起刪除（C2-1）。
 * - 呼叫 DELETE /api/v2/trades/{id}?version=N，標頭帶 Idempotency-Key：
 *   每個按鈕記住自己的 key，逾時後再按一次會用同一個 → 伺服器只刪一次，重送不會變成「找不到」。
 * - 另一個裝置先改過這筆 → 409 VERSION_CONFLICT，不刪除，請重新載入（v2-form.js 的 handleError）。
 * - 刪除後之後的交易會重算；造成某一天賣超 → 伺服器拒絕並說明（OVERSELL）。
 * - 送出中所有刪除按鈕停用，避免連點或同時刪兩筆。
 */
(function () {
	'use strict';

	const buttons = document.querySelectorAll('[data-delete-trade]');
	const alertBox = document.getElementById('page-alert');
	const keys = new Map(); // 交易 id → Idempotency-Key（成功或換內容前沿用）
	let submitting = false;

	function setLocked(locked, current) {
		submitting = locked;
		buttons.forEach(function (button) {
			button.disabled = locked;
		});
		if (current) {
			current.textContent = locked ? '刪除中…' : '刪除';
		}
	}

	buttons.forEach(function (button) {
		button.addEventListener('click', function () {
			if (submitting) {
				return;
			}
			const id = button.getAttribute('data-trade-id');
			const isLast = buttons.length === 1;
			const message = '確定要刪除這筆交易嗎？\n\n' + button.getAttribute('data-description')
				+ '\n\n' + (isLast
					? '這是這一檔的最後一筆交易，刪除後這檔持股也會一起刪除。'
					: '刪除後，這一檔之後的交易會依日期重新計算。');
			if (!window.confirm(message)) {
				return;
			}
			if (!keys.has(id)) {
				keys.set(id, V2Form.newKey());
			}
			setLocked(true, button);
			const url = '/api/v2/trades/' + encodeURIComponent(id)
				+ '?version=' + encodeURIComponent(button.getAttribute('data-version'));
			V2Form.callApi('DELETE', url, null, keys.get(id)).then(function (result) {
				if (result.ok) {
					keys.delete(id);
					const code = button.getAttribute('data-code');
					try {
						sessionStorage.setItem('v2-flash', '已刪除：' + button.getAttribute('data-description')
							+ (result.data.holding_deleted ? '（' + code + ' 已沒有任何交易，持股一併刪除）' : ''));
					} catch (e) {
						// 不能用 sessionStorage 只是少了提示
					}
					// 持股也刪除了 → 回總覽；否則重新載入明細（顯示重算後的數字與新的版本號）
					window.location.href = result.data.holding_deleted ? '/v2' : window.location.pathname;
					return;
				}
				setLocked(false, button);
				if (V2Form.handleError(result, alertBox)) {
					keys.set(id, V2Form.newKey());
				}
			});
		});
	});

	// 按「上一頁」回到這一頁：解除鎖定
	window.addEventListener('pageshow', function (event) {
		if (event.persisted) {
			setLocked(false, null);
			buttons.forEach(function (button) {
				button.textContent = '刪除';
			});
		}
	});
})();
