package io.qiao.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.qiao.entity.Holding;
import io.qiao.exception.DuplicateCodeException;
import io.qiao.exception.HoldingNotFoundException;
import io.qiao.repository.HoldingRepository;

/**
 * 持股的新增、修改、刪除、查詢。
 * 網頁（第 2 題）和 API（第 5 題）共用這一層，規則只寫一次。
 */
@Service
public class HoldingService {

	private final HoldingRepository holdingRepository;
	private final Clock clock;

	public HoldingService(HoldingRepository holdingRepository, Clock clock) {
		this.holdingRepository = holdingRepository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<Holding> findAll() {
		return holdingRepository.findAllByOrderByCodeAsc();
	}

	@Transactional(readOnly = true)
	public Holding findById(Long id) {
		return holdingRepository.findById(id)
				.orElseThrow(() -> new HoldingNotFoundException(id));
	}

	/**
	 * 新增持股。
	 *
	 * @throws DuplicateCodeException 代號已存在
	 */
	@Transactional
	public Holding create(String code, Integer lots, BigDecimal buyPrice) {
		String normalizedCode = normalizeCode(code);

		// 第一道檢查：先查有沒有重複，給使用者清楚的錯誤訊息
		if (holdingRepository.existsByCode(normalizedCode)) {
			throw new DuplicateCodeException(normalizedCode);
		}

		LocalDateTime now = LocalDateTime.now(clock);
		Holding holding = new Holding();
		holding.setCode(normalizedCode);
		holding.setLots(lots);
		holding.setBuyPrice(buyPrice);
		holding.setCreatedAt(now);
		holding.setUpdatedAt(now);

		try {
			// saveAndFlush：立刻寫入資料庫，讓 UNIQUE 限制的錯誤在這裡就發生，才能轉成 DuplicateCodeException
			return holdingRepository.saveAndFlush(holding);
		} catch (DataIntegrityViolationException e) {
			// 第二道檢查（最後防線）：兩個人「同時」新增同一個代號時，第一道檢查都會通過，由資料庫的 UNIQUE 限制擋下
			throw new DuplicateCodeException(normalizedCode);
		}
	}

	/**
	 * 修改持股：只能改張數和買進價，代號不能改（與 App 1.0 API 規格一致）。
	 *
	 * @throws HoldingNotFoundException 找不到這筆持股
	 */
	@Transactional
	public Holding update(Long id, Integer lots, BigDecimal buyPrice) {
		Holding holding = findById(id);
		holding.setLots(lots);
		holding.setBuyPrice(buyPrice);
		holding.setUpdatedAt(LocalDateTime.now(clock));
		// 在交易（@Transactional）內修改查出來的 Entity，結束時 JPA 會自動寫回資料庫，不需要再呼叫 save
		return holding;
	}

	/**
	 * 刪除持股。
	 *
	 * @throws HoldingNotFoundException 找不到這筆持股
	 */
	@Transactional
	public void delete(Long id) {
		Holding holding = findById(id);
		holdingRepository.delete(holding);
	}

	/**
	 * 統一代號格式：去掉前後空白、英文轉大寫（例如「 00632r 」→「00632R」），
	 * 避免同一檔股票因為大小寫或空白不同而被存成兩筆。
	 */
	private String normalizeCode(String code) {
		return code.trim().toUpperCase(Locale.ROOT);
	}
}
