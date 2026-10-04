package cc.meteormc.xposedkit.annotation

/**
 * 用于将一个类注册为 XposedKit 入口的注解
 *
 * 符号处理器会读取注解中的配置，并据此生成模块的必要属性
 *
 * 被注解的类必须继承自 [cc.meteormc.xposedkit.XposedModule]
 *
 * 仅支持注册一个入口类，否则会抛出异常
 *
 * @property minApi 模块支持的最低 API 版本
 * @property targetApi 模块适配的目标 API 版本
 * @property staticScope 是否使用静态作用域，该选项仅在现代 Xposed Api 中有效
 * @property autoHotReload 是否启用自动热重载，该选项仅在现代 Xposed Api 中有效
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class ModuleRegister(
    val minApi: Int = 93,
    val targetApi: Int = 102,
    val staticScope: Boolean = false,
    val autoHotReload: Boolean = false
)