package io.github.jackwener.dsh.feature.settings

/**
 * Vendor ROM detection and the candidate settings screens that stop the ROM from killing DSH in
 * the background. Pure data so it is unit-testable; [KeepAliveLauncher] turns targets into Intents.
 */
object RomKeepAlive {
    enum class Vendor(val zh: String, val en: String) {
        Xiaomi("小米", "Xiaomi"), Huawei("华为", "Huawei"), Honor("荣耀", "Honor"),
        Oppo("OPPO", "OPPO"), OnePlus("一加", "OnePlus"), Realme("真我", "realme"),
        Vivo("vivo", "vivo"), Samsung("三星", "Samsung"), Meizu("魅族", "Meizu"),
        Motorola("摩托罗拉", "Motorola"), Google("Google", "Google"), Other("", "");
        fun label(zh: Boolean) = if (zh) this.zh else en
    }

    enum class Kind { AutoStart, Background, Battery }

    data class Rom(val vendor: Vendor, val os: String?) {
        fun label(zh: Boolean): String {
            // vivo without positive OriginOS evidence: OriginOS 6 on some iQOO/vivo phones still reports Funtouch props,
            // so name neither skin rather than guess wrong.
            if (vendor == Vendor.Vivo && os.isNullOrBlank()) return if (zh) "vivo / iQOO 系统" else "vivo / iQOO OS"
            val v = vendor.label(zh).ifEmpty { if (zh) "Android 设备" else "Android device" }
            return if (os.isNullOrBlank()) v else "$v · $os"
        }
    }

    /** What a keep-alive row can honestly say about its own state. */
    enum class RowStatus { Exempt, NotExempt, Unreadable, ManualCheck }

    /**
     * Only battery optimisation has a public API; vendor autostart / background switches cannot be read, so
     * those rows never claim a state. [readBatteryExempt] throwing means "couldn't read", not "not exempt".
     */
    fun status(kind: Kind, readBatteryExempt: () -> Boolean): RowStatus =
        if (kind != Kind.Battery) RowStatus.ManualCheck
        else runCatching(readBatteryExempt).fold({ if (it) RowStatus.Exempt else RowStatus.NotExempt }, { RowStatus.Unreadable })

    /**
     * One candidate screen. [pkgExtras] / [labelExtras] are extra keys that receive our package
     * name / app label; [pkgUri] adds `package:<ours>` as data.
     */
    data class Target(
        val pkg: String? = null,
        val cls: String? = null,
        val action: String? = null,
        val pkgUri: Boolean = false,
        val pkgExtras: List<String> = emptyList(),
        val labelExtras: List<String> = emptyList(),
    )

    data class Hint(val zh: String, val en: String) {
        fun text(zh: Boolean) = if (zh) this.zh else en
    }

    // Props: MIUI/HyperOS, EMUI/HarmonyOS/MagicOS, ColorOS, OxygenOS, realme UI, vivo, One UI, Flyme.
    val PROPS = listOf(
        "ro.miui.ui.version.name", "ro.mi.os.version.name", "ro.build.version.emui", "hw_sc.build.platform.version",
        "ro.build.version.magic", "ro.build.version.opporom", "ro.build.version.oplusrom", "ro.oxygen.version",
        "ro.build.version.realmeui", "ro.vivo.os.name", "ro.vivo.os.version", "ro.vivo.os.build.display.id", "ro.build.version.oneui",
        "ro.build.display.id",
    )

    fun detect(manufacturer: String?, brand: String?, prop: (String) -> String?): Rom {
        val m = "${manufacturer.orEmpty()} ${brand.orEmpty()}".lowercase()
        fun p(k: String) = runCatching { prop(k) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val display = p("ro.build.display.id").orEmpty()
        val vendor = when {
            "xiaomi" in m || "redmi" in m || "poco" in m || p("ro.miui.ui.version.name") != null || p("ro.mi.os.version.name") != null -> Vendor.Xiaomi
            "honor" in m -> Vendor.Honor
            "huawei" in m || p("ro.build.version.emui") != null || p("hw_sc.build.platform.version") != null -> Vendor.Huawei
            "oneplus" in m -> Vendor.OnePlus
            "realme" in m -> Vendor.Realme
            "oppo" in m || p("ro.build.version.opporom") != null || p("ro.build.version.oplusrom") != null -> Vendor.Oppo
            "vivo" in m || "iqoo" in m || p("ro.vivo.os.version") != null -> Vendor.Vivo
            "samsung" in m -> Vendor.Samsung
            "meizu" in m || display.contains("flyme", true) -> Vendor.Meizu
            "motorola" in m -> Vendor.Motorola
            "google" in m -> Vendor.Google
            else -> Vendor.Other
        }
        val os = when (vendor) {
            Vendor.Xiaomi -> p("ro.mi.os.version.name")?.let { "HyperOS ${it.removePrefix("OS")}" }
                ?: p("ro.miui.ui.version.name")?.let { "MIUI ${it.removePrefix("V")}" }
            Vendor.Huawei, Vendor.Honor -> p("hw_sc.build.platform.version")?.let { "HarmonyOS $it" }
                ?: p("ro.build.version.magic")?.let { "MagicOS ${it.substringAfter('_')}" }
                ?: p("ro.build.version.emui")?.let { e ->
                    val v = e.substringAfter('_')
                    if (e.startsWith("MagicUI", true)) "MagicUI $v" else "EMUI $v"
                }
            Vendor.Oppo, Vendor.OnePlus, Vendor.Realme -> when {
                vendor == Vendor.OnePlus && p("ro.oxygen.version") != null -> "OxygenOS ${p("ro.oxygen.version")}"
                vendor == Vendor.Realme && p("ro.build.version.realmeui") != null -> "realme UI ${p("ro.build.version.realmeui")!!.removePrefix("V")}"
                else -> (p("ro.build.version.oplusrom") ?: p("ro.build.version.opporom"))?.let { "ColorOS ${it.removePrefix("V")}" }
            }
            // Only positive "OriginOS" evidence is trusted. ro.vivo.os.name says "Funtouch" on some OriginOS 6 builds and
            // ro.vivo.os.version tracks Android there, so without it we stay neutral (see Rom.label).
            Vendor.Vivo -> {
                val origin = listOf(p("ro.vivo.os.name"), p("ro.vivo.os.build.display.id"), display).any { it?.contains("origin", true) == true }
                if (!origin) null
                else p("ro.vivo.os.build.display.id")?.let { Regex("OriginOS\\s*([0-9][0-9.]*)", RegexOption.IGNORE_CASE).find(it) }
                    ?.let { "OriginOS ${it.groupValues[1]}" } ?: "OriginOS"
            }
            Vendor.Samsung -> p("ro.build.version.oneui")?.toIntOrNull()?.takeIf { it >= 10000 }?.let { "One UI ${it / 10000}.${it / 100 % 100}" }
            Vendor.Meizu -> display.takeIf { it.contains("flyme", true) }?.let { Regex("Flyme\\s*(?:OS)?\\s*([0-9][0-9.]*)", RegexOption.IGNORE_CASE).find(it) }
                ?.let { "Flyme ${it.groupValues[1]}" } ?: if (display.contains("flyme", true)) "Flyme" else null
            else -> null
        }
        return Rom(vendor, os)
    }

    /** App info for our package: always present, so it is also the final fallback. */
    val APP_DETAILS = Target(action = "android.settings.APPLICATION_DETAILS_SETTINGS", pkgUri = true)

    private val miuiAutoStart = listOf(
        Target("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        Target(action = "miui.intent.action.APP_PERM_EDITOR", pkgExtras = listOf("extra_pkgname")),
    )
    private val miuiBackground = listOf(
        Target("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            pkgExtras = listOf("package_name"), labelExtras = listOf("package_label")),
        Target("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
    )
    private fun huaweiAutoStart(sm: String) = listOf(
        Target(sm, "$sm.startupmgr.ui.StartupNormalAppListActivity"),
        Target(sm, "$sm.appcontrol.activity.StartupAppControlActivity"),
        Target(sm, "$sm.optimize.process.ProtectActivity"),
    )
    private fun huaweiBackground(sm: String) = listOf(Target(sm, "$sm.power.ui.HwPowerManagerActivity"))
    private val colorAutoStart = listOf(
        Target("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        Target("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        Target("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
    )
    // ColorOS 12+ keeps the per-app switches on App info → 耗电管理; the global battery pages are older fallbacks.
    private val colorBackground = listOf(
        APP_DETAILS,
        Target("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
        Target("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
    )
    private val vivoAutoStart = listOf(
        Target("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        Target("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        Target("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
    )
    private val vivoBackground = listOf(
        Target("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
        Target("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity"),
    )
    private val samsungBackground = listOf("com.samsung.android.lool", "com.samsung.android.sm_cn", "com.samsung.android.sm")
        .map { Target(it, "com.samsung.android.sm.ui.battery.BatteryActivity") }
    private val meizuAutoStart = listOf(Target(action = "com.meizu.safe.security.SHOW_APPSEC", pkgExtras = listOf("packageName")))
    private val meizuBackground = listOf(
        Target("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
        Target("com.meizu.safe", "com.meizu.safe.powerui.PowerAppPermissionActivity"),
    )
    // AOSP per-app battery page (Android 12+ "App battery usage"); hidden action, resolved before use.
    private val aospBackground = listOf(Target(action = "android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", pkgUri = true))

    /** Every vendor package we may probe, for the manifest `<queries>` (kept in sync by a test). */
    val PACKAGES: Set<String> get() = Vendor.entries.flatMap { v -> Kind.entries.flatMap { targets(v, it) } }.mapNotNull { it.pkg }.toSet()

    /** Kinds worth a button on this vendor, in display order. */
    fun kinds(vendor: Vendor): List<Kind> = when (vendor) {
        Vendor.Samsung, Vendor.Motorola, Vendor.Google, Vendor.Other -> listOf(Kind.Background, Kind.Battery)
        else -> listOf(Kind.AutoStart, Kind.Background, Kind.Battery)
    }

    /** Ordered vendor screens for [kind]; the caller falls back to App info when none opens. */
    fun targets(vendor: Vendor, kind: Kind): List<Target> = when (kind) {
        Kind.Battery -> listOf(
            Target(action = "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", pkgUri = true),
            Target(action = "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"),
        )
        Kind.AutoStart -> when (vendor) {
            Vendor.Xiaomi -> miuiAutoStart
            Vendor.Huawei -> huaweiAutoStart("com.huawei.systemmanager")
            Vendor.Honor -> huaweiAutoStart("com.hihonor.systemmanager") + huaweiAutoStart("com.huawei.systemmanager")
            Vendor.OnePlus -> listOf(Target("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")) + colorAutoStart
            Vendor.Oppo, Vendor.Realme -> colorAutoStart
            Vendor.Vivo -> vivoAutoStart
            Vendor.Meizu -> meizuAutoStart
            else -> emptyList()
        }
        Kind.Background -> when (vendor) {
            Vendor.Xiaomi -> miuiBackground
            Vendor.Huawei -> huaweiBackground("com.huawei.systemmanager")
            Vendor.Honor -> huaweiBackground("com.hihonor.systemmanager") + huaweiBackground("com.huawei.systemmanager")
            Vendor.Oppo, Vendor.OnePlus, Vendor.Realme -> colorBackground
            Vendor.Vivo -> vivoBackground
            Vendor.Samsung -> samsungBackground
            Vendor.Meizu -> meizuBackground
            else -> aospBackground
        }
    }

    /** What to switch on the screen we open (or on App info when nothing vendor-specific opens). */
    fun hint(vendor: Vendor, kind: Kind): Hint = when (kind) {
        Kind.Battery -> Hint("允许“不受电池优化限制”，锁屏后也能执行任务", "Allow ignoring battery optimisation so tasks run while locked")
        Kind.AutoStart -> when (vendor) {
            Vendor.Xiaomi -> Hint("自启动 → 打开 DSH", "Autostart → turn DSH on")
            Vendor.Huawei, Vendor.Honor -> Hint("应用启动管理 → DSH → 关闭“自动管理”，允许自启动、关联启动、后台活动",
                "App launch → DSH → turn off Manage automatically, allow auto-launch, secondary launch and run in background")
            Vendor.Oppo, Vendor.OnePlus, Vendor.Realme -> Hint("自启动管理 → 允许 DSH 自启动", "Auto launch → allow DSH")
            Vendor.Vivo -> Hint("后台启动管理 / 自启动 → 允许 DSH", "Background startup / Autostart → allow DSH")
            Vendor.Meizu -> Hint("权限管理 → 后台管理 / 自启动 → 允许后台运行", "Permissions → background management / autostart → allow")
            else -> Hint("此系统无单独的自启动开关", "This system has no separate autostart switch")
        }
        Kind.Background -> when (vendor) {
            Vendor.Xiaomi -> Hint("省电策略 → 无限制", "Battery saver → No restrictions")
            Vendor.Huawei, Vendor.Honor -> Hint("电池 → 更多电池设置 → 休眠时始终保持网络连接；应用启动管理中允许后台活动",
                "Battery → more settings → stay connected while asleep; allow background activity in App launch")
            Vendor.Oppo, Vendor.OnePlus, Vendor.Realme -> Hint("应用信息 → 耗电管理 → 允许完全后台行为、允许自动启动",
                "App info → Battery usage → allow full background activity and auto launch")
            Vendor.Vivo -> Hint("后台耗电管理 → DSH → 允许后台高耗电", "Background power consumption → DSH → allow high background usage")
            Vendor.Samsung -> Hint("后台使用限制 → 从不休眠的应用 → 添加 DSH", "Background usage limits → Never sleeping apps → add DSH")
            Vendor.Meizu -> Hint("后台管理 → DSH → 允许后台运行", "Background management → DSH → allow running in background")
            else -> Hint("应用电池用量 → 不受限制", "App battery usage → Unrestricted")
        }
    }

    /** Locking the card in Recents keeps most Chinese ROMs' one-tap cleaner from killing DSH. */
    fun recentsHint(vendor: Vendor): Hint = when (vendor) {
        Vendor.Xiaomi, Vendor.Huawei, Vendor.Honor, Vendor.Vivo, Vendor.Meizu ->
            Hint("在最近任务中下拉 DSH 卡片加锁，一键清理时不会被结束", "In Recents, pull down the DSH card to lock it so Clear all skips it")
        Vendor.Oppo, Vendor.OnePlus, Vendor.Realme ->
            Hint("在最近任务中点 DSH 卡片右上角菜单 → 锁定", "In Recents, open the DSH card menu → Lock")
        Vendor.Samsung -> Hint("在最近任务中点 DSH 图标 → 保持打开", "In Recents, tap the DSH icon → Keep open")
        else -> Hint("如系统支持，在最近任务中锁定 DSH", "Lock DSH in Recents if your system supports it")
    }
}
