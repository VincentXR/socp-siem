package com.socp.platform.auth.security;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 角色授权注解（RBAC）：标注在 Controller 方法上，要求调用者 JWT role claim 命中
 * 指定角色之一，否则返回 403。
 *
 * <p>角色体系（与网关 /auth/login 签发一致）：
 *  - admin   ：全部操作（含用户/角色管理）
 *  - analyst ：日常安全运营（告警处置、案件、情报、剧本执行）
 *  - viewer  ：只读（网关已有全局只读兜底，此处可对敏感读接口再限制）
 *
 * <p>示例：
 * <pre>{@code
 * @RequireRole("admin")            // 仅管理员
 * @RequireRole({"admin","analyst"}) // 管理员或分析师
 * }</pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    /** 允许的角色列表（命中其一即通过） */
    String[] value() default {};

    /** 未命中时的错误提示 */
    String message() default "无权限执行该操作";
}
