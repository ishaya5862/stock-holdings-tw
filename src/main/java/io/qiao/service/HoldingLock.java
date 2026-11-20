package io.qiao.service;

import java.util.function.Supplier;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

import io.qiao.entity.Holding;
import io.qiao.exception.HoldingBusyException;
import io.qiao.repository.HoldingRepository;

/**
 * 鎖住一筆持股（SELECT … FOR UPDATE，悲觀鎖），2.0 第 6 題 C3-1、C3-2。
 *
 * 為什麼要鎖：手機與電腦同時寫同一檔（例如同時賣出），兩邊各自讀到「目前 1,000 股」都覺得夠賣，
 * 合起來卻賣超。先鎖住持股列，後到的請求會等前一個交易結束，再讀最新的交易紀錄重算，就不會賣超。
 *
 * 規則：
 * - 必須在交易（@Transactional／TransactionTemplate）裡呼叫，鎖會在交易結束（commit 或 rollback）時釋放。
 * - 每次只鎖一檔，不會發生兩個請求互相等待而卡死（死結）。
 * - 等鎖上限 3 秒（application.properties 的 connection-init-sql）；超過 → HoldingBusyException（409 請稍後再試）。
 * - 2.0 的交易服務與 1.0 的修改、刪除都用這裡，兩邊同時操作也一樣排隊。
 */
@Component
public class HoldingLock {

	private final HoldingRepository holdingRepository;

	public HoldingLock(HoldingRepository holdingRepository) {
		this.holdingRepository = holdingRepository;
	}

	/**
	 * 依代號鎖住持股。
	 *
	 * @param notFound 沒有這筆持股時要丟的例外
	 */
	public Holding byCode(String code, Supplier<? extends RuntimeException> notFound) {
		return lock(() -> holdingRepository.findByCodeForUpdate(code).orElseThrow(notFound));
	}

	/**
	 * 依 id 鎖住持股。
	 *
	 * @param notFound 沒有這筆持股時要丟的例外
	 */
	public Holding byId(Long id, Supplier<? extends RuntimeException> notFound) {
		return lock(() -> holdingRepository.findByIdForUpdate(id).orElseThrow(notFound));
	}

	/** 等鎖超過上限時，資料庫的錯誤（MySQL 1205、H2 50200）由 Spring 轉成 PessimisticLockingFailureException */
	private static Holding lock(Supplier<Holding> query) {
		try {
			return query.get();
		} catch (PessimisticLockingFailureException e) {
			throw new HoldingBusyException(e);
		}
	}
}
