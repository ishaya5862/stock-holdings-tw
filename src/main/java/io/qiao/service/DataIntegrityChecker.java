package io.qiao.service;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.qiao.service.PositionQueryService.PositionView;

/**
 * 資料異常主動檢查（優化 #2）：啟動時與每天固定時間掃描全部持股，把「交易時間線不合理、算不出來」的持股
 * 彙總成一筆 log。
 *
 * 為什麼需要：讀取時遇到異常只會把那一檔標成「資料異常」（故障隔離），沒有人打開畫面就不會發現；
 * 讀取時的 log 是每次讀取、每一檔都記一次（WARN），量大又重複，看不出「從什麼時候開始、現在有幾檔」。
 *
 * 為什麼只寫 log、不寄信或發通知（D-32 做法 A）：業界的分工是「程式寫出清楚、好搜尋、不重複的 log，
 * 通知交給部署環境的監控工具」。這裡用固定的開頭 {@link #LOG_TAG}，監控工具設定「看到這個關鍵字的 ERROR 就通知」即可；
 * 不需要外部帳號或 token，另一台電腦照 README 啟動也一樣能用。
 *
 * 異常只有一種：某一筆賣出超過當時持有的股數（直接改資料庫或程式有 bug 時才會發生）。
 * 股數、價格 ≤ 0 等其他錯誤資料，資料表的 CHECK 限制已經擋住，存不進去（V5）。
 */
@Service
public class DataIntegrityChecker {

	/** log 的固定開頭：監控工具用這個關鍵字設定告警；搜尋 log 時也只要找這個字 */
	static final String LOG_TAG = "DATA_INTEGRITY";

	private static final Logger log = LoggerFactory.getLogger(DataIntegrityChecker.class);

	private final PositionQueryService positionQueryService;

	public DataIntegrityChecker(PositionQueryService positionQueryService) {
		this.positionQueryService = positionQueryService;
	}

	/** 啟動完成後檢查一次：重新部署、重開機後馬上知道有沒有問題，不用等到隔天 */
	@EventListener(ApplicationReadyEvent.class)
	public void checkOnStartup() {
		runSafely("啟動時");
	}

	/** 每天固定時間（app.data-check.cron）檢查；zone 指定台北，正式機在海外也是台北時間的 08:00 */
	@Scheduled(cron = "${app.data-check.cron}", zone = "${app.zone}")
	public void checkDaily() {
		runSafely("每日排程");
	}

	/**
	 * 檢查本身失敗（例如資料庫暫時連不上）時只記錄下來，不往外丟：
	 * 啟動時丟出例外會讓整個程式啟動失敗，正常的持股也不能用；排程下次時間到會再檢查。
	 */
	private void runSafely(String trigger) {
		try {
			check(trigger);
		} catch (RuntimeException e) {
			log.error("{} 資料檢查（{}）執行失敗，下次排程會再檢查：{}", LOG_TAG, trigger, e.getMessage(), e);
		}
	}

	/**
	 * 掃描全部持股（含已全部賣光的），有異常寫一筆 ERROR、沒有異常寫一筆 INFO。
	 *
	 * @param trigger 誰觸發的（啟動時／每日排程），寫進 log 方便對照時間
	 * @return 異常的持股，每一檔一行「代號（持股 id）：原因」；沒有異常時是空的
	 */
	List<String> check(String trigger) {
		List<PositionView> views = positionQueryService.findAll();
		List<String> problems = views.stream()
				.filter(PositionView::hasDataError)
				.map(view -> view.holding().getCode() + "（持股 id " + view.holding().getId() + "）：" + view.dataError())
				.toList();

		if (problems.isEmpty()) {
			log.info("{} 資料檢查（{}）：共 {} 檔持股，沒有發現資料異常", LOG_TAG, trigger, views.size());
		} else {
			// 全部寫在同一筆：告警只會觸發一次，內容一次看完
			log.error("{} 資料檢查（{}）：共 {} 檔持股，其中 {} 檔交易資料異常、無法計算，"
					+ "請用 2.0 網頁或 App 2.0 的交易明細修正：{}",
					LOG_TAG, trigger, views.size(), problems.size(), problems.stream().collect(Collectors.joining("；")));
		}
		return problems;
	}
}
