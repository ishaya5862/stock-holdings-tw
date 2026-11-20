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

import io.qiao.dto.HoldingForm;
import io.qiao.entity.Holding;
import io.qiao.exception.DuplicateCodeException;
import io.qiao.exception.QuoteUnavailableException;
import io.qiao.exception.StockNotFoundException;
import io.qiao.service.HoldingService;
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

	/** 清單 */
	@GetMapping
	public String list(Model model) {
		model.addAttribute("holdings", holdingService.findAll());
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
		Holding holding = holdingService.findById(id);

		HoldingForm form = new HoldingForm();
		form.setCode(holding.getCode());
		form.setLots(holding.getLots());
		form.setBuyPrice(holding.getBuyPrice());

		model.addAttribute("holdingForm", form);
		model.addAttribute("holdingId", id);
		return FORM_VIEW;
	}

	/** 修改：送出（代號不能改，只更新張數和買進價） */
	@PostMapping("/{id}")
	public String update(@PathVariable Long id, @Valid @ModelAttribute("holdingForm") HoldingForm form,
			BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
		// 只看張數、買進價的錯誤：代號不能改，送上來的代號不採用，所以代號欄位的錯誤不影響修改
		if (bindingResult.hasFieldErrors("lots") || bindingResult.hasFieldErrors("buyPrice")) {
			// 代號以資料庫為準，避免畫面顯示被竄改過的代號
			form.setCode(holdingService.findById(id).getCode());
			model.addAttribute("holdingId", id);
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
