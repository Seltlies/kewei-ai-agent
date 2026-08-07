package com.kiwi.keweiaiagent.admin.dto;

import com.kiwi.keweiaiagent.account.model.AccountRole;
import com.kiwi.keweiaiagent.account.model.AccountStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Console 账号列表查询条件。Spring MVC 负责绑定搜索、角色、状态和页码，Bean Validation
 * 将页码限制为从 1 开始、每页数量限制为 1～100；前端本期固定请求每页 20 条。
 */
@Getter
@Setter
public class AdminAccountQuery {

    private String keyword;

    private AccountRole role;

    private AccountStatus status;

    @NotNull(message = "页码不能为空")
    @Min(value = 1, message = "页码必须从 1 开始")
    private Integer page = 1;

    @NotNull(message = "每页数量不能为空")
    @Min(value = 1, message = "每页数量不能小于 1")
    @Max(value = 100, message = "每页数量不能超过 100")
    private Integer size = 20;
}
