package cc.meteormc.xposedkit.annotation

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class ModuleRegister(
    val minApi: Int = 93,
    val targetApi: Int,
    val staticScope: Boolean = false,
    val autoHotReload: Boolean = false
)