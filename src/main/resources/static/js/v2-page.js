/*
 * 持股記帳 2.0 新網頁：每一頁共用的小功能（第 6 題 C8）。
 *
 * 1. 成功提示：買進、賣出、修改、刪除成功後跳到別的頁面，訊息放在 sessionStorage，這裡取出顯示一次。
 * 2. 大數字的提示框（D-43）：點一下顯示、再點一下或點別處收起（手機沒有「滑鼠移上去」）。
 *    滑鼠移上去由 CSS 處理（v2.css 的 .v2-tip），這裡只處理「點」。
 * 3. 「顯示已全部賣出」開關（總覽）：隱藏或顯示 0 股的持股，並記住選擇。
 *
 * sessionStorage／localStorage 在無痕模式、被瀏覽器封鎖時可能不能用 → 一律 try/catch，
 * 不能用時只是少了提示或不記得選擇，頁面功能不受影響。
 */
(function () {
	'use strict';

	// ---------- 1. 成功提示 ----------
	let flash = null;
	try {
		flash = sessionStorage.getItem('v2-flash');
		sessionStorage.removeItem('v2-flash'); // 只顯示一次：重新整理就不再出現
	} catch (e) {
		flash = null;
	}
	const flashBox = document.getElementById('v2-flash');
	if (flash && flashBox) {
		document.getElementById('v2-flash-text').textContent = flash; // textContent：不當成 HTML，避免被插入程式碼
		flashBox.classList.remove('d-none');
	}

	// ---------- 2. 大數字的提示框 ----------
	// 一定要放在第 3 段前面：第 3 段在不是總覽時會提早 return，明細頁也需要這段。
	// 監聽整份文件（事件委派）：不用替每個數字各綁一次，之後新增的數字也有效。
	document.addEventListener('click', function (event) {
		const clicked = event.target.closest('.v2-tip');
		// 一次只開一個：先收起其他已打開的提示框（也就是「點別處收起」）
		document.querySelectorAll('.v2-tip.v2-tip-open').forEach(function (tip) {
			if (tip !== clicked) {
				tip.classList.remove('v2-tip-open');
			}
		});
		if (clicked) {
			clicked.classList.toggle('v2-tip-open'); // 再點一下同一個數字就收起
		}
	});

	// ---------- 3. 顯示已全部賣出 ----------
	const toggle = document.getElementById('show-closed');
	if (!toggle) {
		return; // 不是總覽，或沒有 0 股的持股
	}
	const STORAGE_KEY = 'v2-show-closed';
	try {
		const saved = localStorage.getItem(STORAGE_KEY);
		if (saved !== null) {
			toggle.checked = saved === 'true';
		}
	} catch (e) {
		// 不能用就維持預設（顯示）
	}

	function apply() {
		document.querySelectorAll('tr.v2-closed').forEach(function (row) {
			row.classList.toggle('d-none', !toggle.checked);
		});
	}

	toggle.addEventListener('change', function () {
		apply();
		try {
			localStorage.setItem(STORAGE_KEY, String(toggle.checked));
		} catch (e) {
			// 記不住選擇不影響功能
		}
	});
	apply();
})();
