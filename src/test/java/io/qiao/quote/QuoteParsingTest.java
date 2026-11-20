package io.qiao.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * 官方格式解析的小工具測試：民國年、千分位、沒有成交（--）、股票名稱。
 */
class QuoteParsingTest {

	@Test
	void 民國年轉西元() {
		assertThat(QuoteParsing.parseRocDate("115/11/19")).isEqualTo(LocalDate.of(2026, 11, 19));
	}

	@Test
	void 日期格式不正確_丟出格式錯誤() {
		assertThatThrownBy(() -> QuoteParsing.parseRocDate("2026-11-19"))
				.isInstanceOf(QuoteFormatException.class);
	}

	@Test
	void 價格去掉千分位逗號() {
		assertThat(QuoteParsing.parsePrice("2,330.00")).isEqualByComparingTo("2330.00");
		assertThat(QuoteParsing.parsePrice("94.10")).isEqualByComparingTo("94.10");
	}

	@Test
	void 沒有成交的價格_回傳null() {
		assertThat(QuoteParsing.parsePrice("--")).isNull();
		assertThat(QuoteParsing.parsePrice("----")).isNull();
		assertThat(QuoteParsing.parsePrice("")).isNull();
	}

	@Test
	void 證交所title取出股票名稱() {
		assertThat(TwseQuoteClient.parseName("115年11月 2330 台積電           各日成交資訊", "2330")).isEqualTo("台積電");
		assertThat(TwseQuoteClient.parseName("115年11月 0050 元大台灣50       各日成交資訊", "0050")).isEqualTo("元大台灣50");
	}

	@Test
	void 證交所title格式不如預期_退而用代號() {
		assertThat(TwseQuoteClient.parseName("", "2330")).isEqualTo("2330");
	}
}
