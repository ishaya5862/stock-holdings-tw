package io.qiao.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import io.qiao.calculation.ProfitCalculator;
import io.qiao.dto.HoldingForm;
import io.qiao.dto.HoldingListView;
import io.qiao.dto.HoldingView;
import io.qiao.entity.Holding;
import io.qiao.exception.DuplicateCodeException;
import io.qiao.exception.QuoteUnavailableException;
import io.qiao.exception.StockNotFoundException;
import io.qiao.service.HoldingService;
import io.qiao.validation.StockCodes;
import jakarta.validation.Valid;

/**
 * 持股清單頁面（網頁版的新增、修改、刪除、查詢）。
 *
 * 存檔成功後一律「轉址」回清單（PRG：Post → Redirect → Get），
 * 避免使用者按 F5 重新整理時，瀏覽器重複送出表單。
 * 找不到持股（HoldingNotFoundException）由 WebExceptionHandler 統一處理。
 */
@Controller
@RequestMapping("/holdings")
public class HoldingController {

	private static final String FORM_VIEW = "holdings/form";

	private final HoldingService holdingService;

	public HoldingController(HoldingService holdingService) {
		this.holdingService = holdingService;
	}

	/**
	 * 這個 Controller 的每個畫面都會帶上前端需要的規則（@ModelAttribute 方法：每次處理請求前都會執行，
	 * 驗證失敗重新顯示表單時也會有）：
	 * - 股票代號規則：表單頁的前端即時檢查
	 * - 手續費率、最低手續費：表單頁的「預估買進成本」即時試算（取自 ProfitCalculator，前端不自己寫死）
	 */
	@ModelAttribute
	public void addFrontendRules(Model model) {
		model.addAttribute("codePattern", StockCodes.PATTERN);
		model.addAttribute("codeMessage", StockCodes.MESSAGE);
		// toPlainString：避免 BigDecimal 輸出成科學記號（例如 1.425E-3），前端才能正確解析
		model.addAttribute("feeRate", ProfitCalculator.FEE_RATE.toPlainString());
		model.addAttribute("minFee", ProfitCalculator.MIN_FEE.toPlainString());
	}

	/** 清單（收盤價只讀記憶體快取，不會每次打開就查官方） */
	@GetMapping
	public String list(Model model) {
		HoldingListView listView = holdingService.findListWithSummary();
		model.addAttribute("holdings", listView.holdings());
		model.addAttribute("summary", listView.summary());
		return "holdings/list";
	}

	/** 新增：顯示空白表單 */
	@GetMapping("/new")
	public String newForm(Model model) {
		model.addAttribute("holdingForm", new HoldingForm());
		return FORM_VIEW;
	}

	/** 新增：送出 */
	@PostMapping
	public String create(@Valid @ModelAttribute("holdingForm") HoldingForm form, BindingResult bindingResult,
			RedirectAttributes redirectAttributes) {
		// 驗證失敗：回到表單，顯示錯誤，並保留使用者已輸入的內容
		if (bindingResult.hasErrors()) {
			return FORM_VIEW;
		}

		Holding saved;
		try {
			saved = holdingService.create(form.getCode(), form.getLots(), form.getBuyPrice());
		} catch (DuplicateCodeException | StockNotFoundException e) {
			// 代號重複、上市上櫃都查不到：錯誤顯示在代號欄位旁，資料不儲存
			bindingResult.rejectValue("code", "invalidCode", e.getMessage());
			return FORM_VIEW;
		} catch (QuoteUnavailableException e) {
			// 官方連不上：不是使用者輸入錯誤，顯示在表單上方，資料不儲存
			bindingResult.reject("quoteUnavailable", QuoteUnavailableException.USER_MESSAGE);
			return FORM_VIEW;
		}

		redirectAttributes.addFlashAttribute("successMessage", "已新增 " + saved.getCode());
		return "redirect:/holdings";
	}

	/** 修改：顯示表單（帶入目前的值） */
	@GetMapping("/{id}/edit")
	public String editForm(@PathVariable Long id, Model model) {
		HoldingView holding = holdingService.findViewById(id);

		HoldingForm form = new HoldingForm();
		form.setLots(holding.lots());
		form.setBuyPrice(holding.buyPrice());

		model.addAttribute("holdingForm", form);
		addEditPageInfo(model, id, holding);
		return FORM_VIEW;
	}

	/**
	 * 修改頁需要的資料：持股 id（表單送出網址）＋股票資訊（畫面上方顯示「代號、名稱、市場」）。
	 * 代號不放在表單欄位裡（避免被誤會可以修改），只顯示；送出時也不送代號，後端本來就以資料庫為準。
	 */
	private void addEditPageInfo(Model model, Long id, HoldingView holding) {
		model.addAttribute("holdingId", id);
		model.addAttribute("stockInfo", holding);
	}

	/** 修改：送出（代號不能改，只更新張數和買進價） */
	@PostMapping("/{id}")
	public String update(@PathVariable Long id, @Valid @ModelAttribute("holdingForm") HoldingForm form,
			BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
		// 只看張數、買進價的錯誤：修改頁不送代號（就算有人自己加上也不採用），代號欄位的錯誤不影響修改
		if (bindingResult.hasFieldErrors("lots") || bindingResult.hasFieldErrors("buyPrice")) {
			// 股票資訊從資料庫重新讀，畫面上方的代號、名稱一定是正確的
			addEditPageInfo(model, id, holdingService.findViewById(id));
			return FORM_VIEW;
		}

		Holding updated = holdingService.update(id, form.getLots(), form.getBuyPrice());
		redirectAttributes.addFlashAttribute("successMessage", "已修改 " + updated.getCode());
		return "redirect:/holdings";
	}

	/** 刪除（畫面上會先跳出確認視窗） */
	@PostMapping("/{id}/delete")
	public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
		String code = holdingService.findById(id).getCode();
		holdingService.delete(id);
		redirectAttributes.addFlashAttribute("successMessage", "已刪除 " + code);
		return "redirect:/holdings";
	}
}
