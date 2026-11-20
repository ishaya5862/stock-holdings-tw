package io.qiao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import io.qiao.entity.Holding;
import io.qiao.exception.DuplicateCodeException;
import io.qiao.exception.HoldingNotFoundException;
import io.qiao.repository.HoldingRepository;

/**
 * 持股 Service 測試（H2 記憶體資料庫，Flyway 一樣會建表）。
 * 每個測試都加 @Transactional，結束後自動復原資料，測試之間互不影響。
 */
@SpringBootTest
@Transactional
class HoldingServiceTest {

	@Autowired
	private HoldingService holdingService;

	@Autowired
	private HoldingRepository holdingRepository;

	@Test
	void 新增持股_代號去空白並轉大寫() {
		Holding saved = holdingService.create(" 00632r ", 2, new BigDecimal("15.50"));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getCode()).isEqualTo("00632R");
		assertThat(saved.getLots()).isEqualTo(2);
		assertThat(saved.getBuyPrice()).isEqualByComparingTo("15.50");
		assertThat(saved.getCreatedAt()).isNotNull();
	}

	@Test
	void 新增持股_代號開頭的0會保留() {
		Holding saved = holdingService.create("0050", 1, new BigDecimal("150"));

		assertThat(saved.getCode()).isEqualTo("0050");
	}

	@Test
	void 重複新增同一代號_丟出例外且不儲存() {
		holdingService.create("2330", 1, new BigDecimal("1000"));

		assertThatThrownBy(() -> holdingService.create("2330", 2, new BigDecimal("900")))
				.isInstanceOf(DuplicateCodeException.class)
				.hasMessage("2330 已在持股中");
		assertThat(holdingRepository.count()).isEqualTo(1);
	}

	@Test
	void 重複判斷不受空白和大小寫影響() {
		holdingService.create("00632R", 1, new BigDecimal("15"));

		assertThatThrownBy(() -> holdingService.create(" 00632r", 1, new BigDecimal("15")))
				.isInstanceOf(DuplicateCodeException.class);
	}

	@Test
	void 修改持股_只改張數和買進價() {
		Holding saved = holdingService.create("2330", 1, new BigDecimal("1000"));

		Holding updated = holdingService.update(saved.getId(), 3, new BigDecimal("980.50"));

		assertThat(updated.getCode()).isEqualTo("2330");
		assertThat(updated.getLots()).isEqualTo(3);
		assertThat(updated.getBuyPrice()).isEqualByComparingTo("980.50");
	}

	@Test
	void 修改不存在的持股_丟出找不到() {
		assertThatThrownBy(() -> holdingService.update(99999L, 1, BigDecimal.TEN))
				.isInstanceOf(HoldingNotFoundException.class);
	}

	@Test
	void 刪除持股() {
		Holding saved = holdingService.create("2330", 1, new BigDecimal("1000"));

		holdingService.delete(saved.getId());

		assertThat(holdingRepository.existsById(saved.getId())).isFalse();
	}

	@Test
	void 刪除不存在的持股_丟出找不到() {
		assertThatThrownBy(() -> holdingService.delete(99999L))
				.isInstanceOf(HoldingNotFoundException.class);
	}
}
