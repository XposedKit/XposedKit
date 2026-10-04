package cc.meteormc.xposedkit.annotation

/**
 * 用于将一个类标记为模块设置 Activity 的注解
 *
 * 被注解的类会在模块清单中被注册活动所需的代码
 *
 * 被注解的类必须继承自 [android.app.Activity]
 *
 * 仅支持注解在一个类上，其余的类会被忽略
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class ModuleSettingsActivity
