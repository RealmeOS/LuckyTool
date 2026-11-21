@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.luckyzyx.luckytool.hook.scopes.systemui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.view.View
import android.view.ViewGroup
import com.highcapable.yukihookapi.extension.kavaref.hook
import com.highcapable.yukihookapi.extension.kavaref.toClass
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import com.luckyzyx.luckytool.utils.DexkitUtils
import com.luckyzyx.luckytool.utils.ModulePrefs
import com.luckyzyx.luckytool.utils.PackageUtils
import com.luckyzyx.luckytool.utils.SDK
import org.lsposed.lsparanoid.Obfuscate
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

private const val GLASS_DOWNSCALE = 13
private const val GLASS_BLUR_ALPHA = 11

private fun centerCropRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
    val srcRatio = srcW.toFloat() / srcH.toFloat().coerceAtLeast(1f)
    val dstRatio = dstW.toFloat() / dstH.toFloat().coerceAtLeast(1f)
    return if (srcRatio > dstRatio) {
        val cropW = (srcH * dstRatio).toInt().coerceAtLeast(1)
        val left = (srcW - cropW) / 2
        Rect(left, 0, left + cropW, srcH)
    } else {
        val cropH = (srcW / dstRatio).toInt().coerceAtLeast(1)
        val top = (srcH - cropH) / 2
        Rect(0, top, srcW, top + cropH)
    }
}

private fun fastBlur(bitmap: Bitmap, radius: Int): Bitmap {
    val smallW = (bitmap.width / GLASS_DOWNSCALE).coerceAtLeast(1)
    val smallH = (bitmap.height / GLASS_DOWNSCALE).coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(bitmap, smallW, smallH, true)
    val blurred = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(blurred)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    for (dx in -radius..radius step 4) {
        for (dy in -radius..radius step 4) {
            paint.alpha = GLASS_BLUR_ALPHA
            canvas.drawBitmap(
                small, dx.toFloat() / GLASS_DOWNSCALE,
                dy.toFloat() / GLASS_DOWNSCALE, paint
            )
        }
    }
    return Bitmap.createScaledBitmap(blurred, bitmap.width, bitmap.height, true)
}

/**
 * 媒体音乐流体云玻璃效果（Android 17 / SDK 37）
 *
 * 全部判定走官方语义，不做尺寸、标题、包名、延迟推断：
 * - 有无封面：官方 picture 解析返回 null / Pair（只读 Notification.Bundle 的 "picture" Icon）；
 * - 图片模型：位图模型=封面，Drawable/图标模型=无封面；
 * - 状态挂在真正绘制的 CapsuleView 上，随官方换绑隔离，多会话不串图；
 * - 切换/抢占：CapsuleContainer 的 uiState 入口 + viewItem 换绑；
 * - 缩回/展开：CapsuleView.setHidden + setCapsuleMiniViewShown。
 *
 * OTA 排查：plugin classloader -> anchor missing -> glass effect ready anchors=n/10；
 * 切换异常看 uiState/setHidden，缩回/展开异常看 setCapsuleMiniViewShown。
 *
 * 目标在 SystemUIPlugin.apk，随 OTA 重混淆；混淆类全部由 DexKit 语义锚点解析。
 */
@Obfuscate
object MediaMusicFluidCloudGlassEffect : YukiBaseHooker() {
    private const val TAG = "LuckyTool"

    private const val PLUGIN_PACKAGE = "com.oplus.systemui.plugins"
    private const val MUSIC_SERVICE_ID = "268452006"
    private const val PLUGIN_CLASS_LOADER_FACTORY =
        "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory"

    // 非混淆模型包：图片模型图标载荷的标记接口
    private const val ICON_PAYLOAD_TYPE =
        "com.oplus.systemui.plugins.shared.template.component.model.h"

    private const val MAX_OFFICIAL_PICTURES = 64

    private const val MAX_SLOTS_PER_CAPSULE = 6

    // 非混淆类：插件内保持原始类名，可直接沿用
    private const val CAPSULE_VIEW =
        "com.oplus.systemui.plugins.seedling.capsule.ui.view.CapsuleView"
    private const val CAPSULE_CONTAINER =
        "com.oplus.systemui.plugins.seedling.capsule.ui.view.CapsuleContainer"

    // 官方资源名与模块名：用来只认主图模块，头部图标/装饰模块一律不参与背景决策
    private const val PICTURE_ENTRY_NAME = "img_primary"
    private const val PICTURE_MODULE_NAME = "pic"
    private const val SEEDLING_PLUGIN_MANAGER =
        "com.oplus.systemui.statusbar.seeding.SeedlingPluginManager"
    private const val FLAVOR_ONE_CAPSULE_CONTROLLER =
        "com.oplus.systemui.capsule.FlavorOneCapsuleController"
    private const val CUSTOM_LOTTIE_VIEW =
        "com.oplus.systemui.plugins.shared.template.component.view.CustomLottieView"

    // 玻璃参数
    private const val GLASS_BLUR_RADIUS = 36
    private const val GLASS_WHITE_ALPHA = 60
    private const val GLASS_SOFT_WHITE_ALPHA = 12
    private const val GLASS_EDGE_LIGHT_ALPHA = 16
    private const val GLASS_SATURATION_ALPHA = 0
    private const val GLASS_AVERAGE_TINT_ALPHA = 72
    private const val GLASS_AVERAGE_TINT_LIGHTEN = 18
    private const val GLASS_HIGHLIGHT_THRESHOLD = 205
    private const val GLASS_HIGHLIGHT_DESAT_THRESHOLD = 32
    private const val GLASS_HIGHLIGHT_TINT_PERCENT = 45
    private const val GLASS_HIGHLIGHT_DARKEN_PERCENT = 82

    // 运行期状态：全部以 View 实体为 key
    private val originalBackgrounds = WeakHashMap<View, Drawable?>()
    private val glassDrawables = WeakHashMap<View, GlassDrawable>()
    private val glassSources = WeakHashMap<View, Bitmap>()

    // 官方图片状态挂在真正绘制的 CapsuleView 上，切歌/切应用/缩回/展开由官方重绑，多会话不串图。
    private val artByCapsule = WeakHashMap<View, Bitmap>()
    private val noArtByCapsule = WeakHashMap<View, Boolean>()

    // 图片控件挂载前暂存官方封面，进窗口后按官方 key 认领
    private val pendingArtByLottie = WeakHashMap<View, Bitmap>()
    private val pendingSeqByLottie = WeakHashMap<View, Long>()

    // 内容视图实例变化 = 官方把胶囊换绑到另一条会话（切换/抢占判定）
    private val sessionViewByCapsule = WeakHashMap<View, View>()

    // 官方 picture 解析产出的真封面位图，按实例 + 内容指纹识别
    private val officialPictures = Collections.synchronizedMap(WeakHashMap<Bitmap, Boolean>())
    private val officialPictureSignatures = HashMap<Long, Long>()
    private val signatureByBitmap = Collections.synchronizedMap(WeakHashMap<Bitmap, Long>())

    // 官方模型里出现过的默认图标，直接落图时排除，避免图标被玻璃化
    private val iconSignatures = LinkedHashSet<Long>()

    // 图片模型上的图标载荷方法，名称随 OTA 变化
    private val iconPayloadMethodByClass = WeakHashMap<Class<*>, Method>()

    // CapsuleView -> 它内部的全部 CustomLottieView（一个胶囊有图片槽 + 动画槽）
    private val lottiesByCapsule = WeakHashMap<View, MutableList<View>>()

    // CapsuleView -> 当前官方 viewItem / key；只按官方 key 字符串判断换绑
    private val itemByCapsule = WeakHashMap<View, Any>()
    private val keyByCapsule = WeakHashMap<View, String>()

    // 缩回/展开会连续两次触发 uiState：先清空列表，再按相同 key 重建胶囊。
    // 只跨这两次同步调用保留封面，重建后立即释放。
    private val carryArtByKey = HashMap<String, Bitmap>()

    // 新会话的位图模型先于新胶囊到达时，作废旧会话封面，避免被上一应用封面覆盖。
    @Volatile
    private var preCapsulePictureSeq = 0L

    @Volatile
    private var carryPictureSeq = -1L

    @Volatile
    private var modelSeqCounter = 0L

    @Volatile
    private var lastAlbumBitmap: Bitmap? = null

    @Volatile
    private var lastPreparedAlbum: PreparedAlbum? = null

    private val initLock = Any()

    // 每个真实插件 ClassLoader 只安装一次；官方 reload 后会换新 loader。
    private val initializedLoaders =
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())

    // 运行期解析出的语义句柄
    private var pictureModuleViewField: Field? = null
    private var pictureModuleNameField: Field? = null
    private var viewItemMethod: Method? = null

    // 官方图片模型：位图模型 / Drawable 模型
    @Volatile
    private var bitmapModelClass: Class<*>? = null

    @Volatile
    private var drawableModelClass: Class<*>? = null

    override fun onHook() {
        if (SDK != 37) return
        if (!preferences(ModulePrefs)
                .getBoolean("media_music_fluid_cloud_glass_effect", false)
        ) return

        // 官方在创建插件 ClassLoader 后才会加载 CapsuleView。
        // 在这里同步安装，赶在插件首次创建胶囊之前；onPluginConnected 仅作兜底。
        if (!hookPluginClassLoader()) {
            YLog.debug("plugin classloader entry unavailable, skip glass effect", tag = TAG)
        }
        installLoaderProbe(SEEDLING_PLUGIN_MANAGER, "onPluginConnected")
        installLoaderProbe(FLAVOR_ONE_CAPSULE_CONTROLLER, "onPluginConnected")
    }

    private fun hookPluginClassLoader(): Boolean {
        val factory = runCatching { PLUGIN_CLASS_LOADER_FACTORY.toClass() }.getOrNull()
            ?: return false
        val method = factory.declaredMethods.firstOrNull {
            it.name == "createClassLoader" &&
                it.parameterCount == 0 &&
                ClassLoader::class.java.isAssignableFrom(it.returnType)
        } ?: return false
        return runCatching {
            method.hook {
                after {
                    (result as? ClassLoader)?.let { startInit(it) }
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun installLoaderProbe(className: String, methodName: String): Boolean {
        val clazz = runCatching { className.toClass() }.getOrNull() ?: return false
        val targets = clazz.declaredMethods.filter { method ->
            method.name == methodName &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[1] == Context::class.java
        }
        if (targets.isEmpty()) return false
        return runCatching {
            targets.forEach { method ->
                method.hook {
                    before {
                        args.getOrNull(0)?.javaClass?.classLoader?.let { startInit(it) }
                        (args.getOrNull(1) as? Context)?.classLoader?.let { startInit(it) }
                    }
                }
            }
            true
        }.onFailure {
            YLog.debug("plugin loader probe failed: ${it.message}", tag = TAG)
        }.getOrDefault(false)
    }

    private fun startInit(loader: ClassLoader) {
        if (runCatching { loader.loadClass(CAPSULE_VIEW) }.isFailure) return
        synchronized(initLock) {
            if (loader in initializedLoaders) return
            val installed = runCatching { installAll(loader) }
                .onFailure {
                    YLog.debug("glass effect init failed: ${it.message}", tag = TAG)
                }
                .getOrDefault(false)
            if (installed) initializedLoaders.add(loader)
        }
    }

    private fun installAll(loader: ClassLoader): Boolean {
        val pluginPath = resolvePluginPath(loader) ?: run {
            YLog.debug("plugin apk path unavailable, skip glass effect", tag = TAG)
            return false
        }
        val anchors = runCatching {
            DexkitUtils.create(pluginPath).use { resolveAnchors(it, loader) }
        }.getOrNull() ?: run {
            YLog.debug("plugin anchor resolution failed, skip glass effect", tag = TAG)
            return false
        }
        anchors.pictureModuleSetMethod?.let { hookPictureModule(it) }
        anchors.capsuleViewDrawMethod?.let { hookCapsuleViewDraw(it) }
        anchors.capsuleViewOnDrawMethod?.let { hookCapsuleViewOnDraw(it) }
        anchors.capsuleViewAttachMethod?.let { hookCapsuleViewAttach(it) }
        anchors.lottieImageMethod?.let { hookLottieDrawable(it) }
        anchors.lottieBitmapMethod?.let { hookLottieBitmap(it) }
        anchors.pictureResolverMethod?.let { hookPictureResolver(it) }
        anchors.capsuleContainerUiStateMethod?.let { hookCapsuleContainerUiState(it) }
        anchors.capsuleViewHiddenMethod?.let { hookCapsuleViewHidden(it) }
        anchors.capsuleMiniViewShownMethod?.let { hookCapsuleMiniViewShown(it) }
        val anchorCount = listOf(
            anchors.pictureModuleSetMethod,
            anchors.capsuleViewDrawMethod,
            anchors.capsuleViewOnDrawMethod,
            anchors.capsuleViewAttachMethod,
            anchors.lottieImageMethod,
            anchors.lottieBitmapMethod,
            anchors.pictureResolverMethod,
            anchors.capsuleContainerUiStateMethod,
            anchors.capsuleViewHiddenMethod,
            anchors.capsuleMiniViewShownMethod,
        ).count { it != null }
        YLog.info(
            "glass effect ready: anchors=$anchorCount/10 " +
                "bitmapModel=${anchors.bitmapModelClass != null} " +
                "drawableModel=${anchors.drawableModelClass != null}",
            tag = TAG
        )
        return true
    }

    private fun resolvePluginPath(loader: ClassLoader): String? {
        hostApplication?.packageManager?.let { pm ->
            runCatching {
                PackageUtils(pm).getApplicationInfo(PLUGIN_PACKAGE, 0)?.sourceDir
            }.getOrNull()?.let { return it }
        }
        return runCatching {
            val clazz = loader.loadClass(CAPSULE_VIEW)
            clazz.protectionDomain?.codeSource?.location?.path
        }.getOrNull()
    }

    private class Anchors(
        val bitmapModelClass: Class<*>?,
        val drawableModelClass: Class<*>?,
        val pictureModuleSetMethod: Method?,
        val capsuleViewDrawMethod: Method?,
        val capsuleViewOnDrawMethod: Method?,
        val capsuleViewAttachMethod: Method?,
        val lottieImageMethod: Method?,
        val lottieBitmapMethod: Method?,
        val pictureResolverMethod: Method?,
        val capsuleContainerUiStateMethod: Method?,
        val capsuleViewHiddenMethod: Method?,
        val capsuleMiniViewShownMethod: Method?,
    )

    private fun resolveAnchors(bridge: DexKitBridge, loader: ClassLoader): Anchors {
        // 1) 官方图片模型：toString() 里的类型前缀是源码写死的稳定常量
        val bitmapModel = bridge.findClass {
            matcher { usingStrings(listOf("BitmapModel(src="), StringMatchType.Contains) }
        }.singleOrNull()?.let { load(it.name, loader) }
        val drawableModel = bridge.findClass {
            matcher { usingStrings(listOf("DrawableModel(src="), StringMatchType.Contains) }
        }.singleOrNull()?.let { load(it.name, loader) }
        if (bitmapModel == null) {
            YLog.debug("anchor missing: BitmapModel", tag = TAG)
        }
        bitmapModelClass = bitmapModel
        drawableModelClass = drawableModel

        // 2) PictureModule 落图：单参方法内部同时调用 setImageBitmap / setImageDrawable，
        //    声明类持有 CustomLottieView 字段（胶囊左侧真正显示的图片）
        val pictureModuleSetMethod = runCatching {
            bridge.findMethod {
                matcher {
                    paramCount(1)
                    addInvoke { name("setImageBitmap") }
                    addInvoke { name("setImageDrawable") }
                }
            }.mapNotNull { data ->
                runCatching { data.getMethodInstance(loader) }.getOrNull()
            }.firstOrNull { method ->
                method.declaringClass.declaredFields.any {
                    it.type.name == CUSTOM_LOTTIE_VIEW
                }
            }
        }.getOrNull()
        if (pictureModuleSetMethod != null) {
            val moduleFields = pictureModuleSetMethod.declaringClass.declaredFields
            pictureModuleViewField = moduleFields
                .firstOrNull { it.type.name == CUSTOM_LOTTIE_VIEW }
                ?.apply { isAccessible = true }
            pictureModuleNameField = moduleFields
                .firstOrNull { it.type == String::class.java }
                ?.apply { isAccessible = true }
        } else {
            YLog.debug("anchor missing: PictureModule set method", tag = TAG)
        }

        // 3) CapsuleView（类名非混淆）：每帧布局 drawable 的入口 + onDraw 兜底
        val capsuleView = load(CAPSULE_VIEW, loader)
        val capsuleViewDrawMethod = capsuleView?.declaredMethods?.firstOrNull {
            it.name == "setCapsuleDrawableWidth" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == Integer.TYPE &&
                it.returnType == Void.TYPE
        }
        val capsuleViewOnDrawMethod = capsuleView?.declaredMethods?.firstOrNull {
            it.name == "onDraw" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == Canvas::class.java
        }
        if (capsuleViewDrawMethod == null) {
            YLog.debug("anchor missing: CapsuleView.setCapsuleDrawableWidth", tag = TAG)
        }
        if (capsuleViewOnDrawMethod == null) {
            YLog.debug("anchor missing: CapsuleView.onDraw", tag = TAG)
        }
        val capsuleViewAttachMethod = capsuleView?.declaredMethods?.firstOrNull {
            it.name == "onAttachedToWindow" &&
                it.parameterCount == 0 &&
                it.returnType == Void.TYPE
        }
        val lottieImageMethod = load(CUSTOM_LOTTIE_VIEW, loader)
            ?.declaredMethods?.firstOrNull {
                it.name == "setImageDrawable" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Drawable::class.java
            }
        var lottieType: Class<*>? = load(CUSTOM_LOTTIE_VIEW, loader)
        var lottieBitmapMethod: Method? = null
        while (lottieType != null && lottieBitmapMethod == null) {
            lottieBitmapMethod = lottieType.declaredMethods.firstOrNull {
                it.name == "setImageBitmap" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Bitmap::class.java
            }
            lottieType = lottieType.superclass
        }
        if (lottieBitmapMethod == null) {
            YLog.debug("anchor missing: Lottie setImageBitmap", tag = TAG)
        }
        if (capsuleViewAttachMethod == null) {
            YLog.debug("anchor missing: CapsuleView.onAttachedToWindow", tag = TAG)
        }

        // 4) 官方封面解析：bundle["picture"] -> Pair(数据模型, 位图模型)
        //    取不到 picture 时直接返回 null，这是官方唯一的"有没有封面"判定
        val pictureResolverMethod = runCatching {
            bridge.findMethod {
                matcher {
                    paramCount(2)
                    usingStrings("picture")
                }
            }.mapNotNull { data ->
                runCatching { data.getMethodInstance(loader) }.getOrNull()
            }.firstOrNull { method ->
                method.parameterTypes[0] == String::class.java &&
                    method.returnType.name == "kotlin.Pair"
            }
        }.getOrNull()
        if (pictureResolverMethod == null) {
            YLog.debug("anchor missing: picture resolver", tag = TAG)
        }

        // 5) 官方切换/缩回/展开入口：
        //    CapsuleContainer 的 uiState 入口重建胶囊列表并决定当前可见项，
        //    CapsuleView.setHidden(Z) 是每个胶囊的官方可见标记，
        //    setCapsuleMiniViewShown(Z) 是缩回/展开。
        val capsuleContainer = load(CAPSULE_CONTAINER, loader)
        val capsuleContainerUiStateMethod = capsuleContainer?.declaredMethods?.firstOrNull {
            it.parameterCount == 2 &&
                it.parameterTypes[1] == java.lang.Boolean.TYPE &&
                it.returnType == Void.TYPE &&
                it.parameterTypes[0].name.startsWith(
                    "com.oplus.systemui.plugins.seedling.capsule.ui.model."
                )
        }
        val capsuleViewHiddenMethod = capsuleView?.declaredMethods?.firstOrNull {
            it.name == "setHidden" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == java.lang.Boolean.TYPE &&
                it.returnType == Void.TYPE
        }
        val capsuleMiniViewShownMethod = capsuleContainer?.declaredMethods?.firstOrNull {
            it.name == "setCapsuleMiniViewShown" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == java.lang.Boolean.TYPE &&
                it.returnType == Void.TYPE
        }
        if (capsuleContainerUiStateMethod == null) {
            YLog.debug("anchor missing: CapsuleContainer uiState", tag = TAG)
        }
        if (capsuleViewHiddenMethod == null) {
            YLog.debug("anchor missing: CapsuleView.setHidden", tag = TAG)
        }
        if (capsuleMiniViewShownMethod == null) {
            YLog.debug("anchor missing: setCapsuleMiniViewShown", tag = TAG)
        }
        return Anchors(
            bitmapModelClass = bitmapModel,
            drawableModelClass = drawableModel,
            pictureModuleSetMethod = pictureModuleSetMethod,
            capsuleViewDrawMethod = capsuleViewDrawMethod,
            capsuleViewOnDrawMethod = capsuleViewOnDrawMethod,
            capsuleViewAttachMethod = capsuleViewAttachMethod,
            lottieImageMethod = lottieImageMethod,
            lottieBitmapMethod = lottieBitmapMethod,
            pictureResolverMethod = pictureResolverMethod,
            capsuleContainerUiStateMethod = capsuleContainerUiStateMethod,
            capsuleViewHiddenMethod = capsuleViewHiddenMethod,
            capsuleMiniViewShownMethod = capsuleMiniViewShownMethod,
        )
    }

    private fun load(name: String?, loader: ClassLoader): Class<*>? =
        name?.let { runCatching { loader.loadClass(it) }.getOrNull() }

    /**
     * 官方把图片设进胶囊左侧 CustomLottieView 的唯一入口。
     * 入参是官方图片模型：位图模型（picture 分支）或 Drawable 模型（无封面/兜底）。
     */
    private fun hookPictureModule(method: Method) {
        method.hook {
            after {
                val data = args.getOrNull(0) ?: return@after
                val module = instance
                val lottie = pictureModuleViewField?.let {
                    runCatching { it.get(module) }.getOrNull()
                } as? View ?: return@after
                val moduleName = pictureModuleNameField?.let {
                    runCatching { it.get(module) }.getOrNull() as? String
                }
                val viewEntry = resourceEntryName(lottie)
                val isMainPicture =
                    moduleName == PICTURE_MODULE_NAME || viewEntry == PICTURE_ENTRY_NAME
                val capsule = findParentCapsuleView(lottie)
                capsule?.let { registerLottie(it, lottie) }
                val key = capsule?.let { capsuleKeyOf(it) }
                val isBitmapModel = bitmapModelClass?.isInstance(data) == true
                val isDrawableModel = drawableModelClass?.isInstance(data) == true
                val bitmap = if (isBitmapModel) {
                    modelField(data, Bitmap::class.java) as? Bitmap
                } else {
                    null
                }
                val art = bitmap?.takeIf { !it.isRecycled }
                val icon = if (isDrawableModel) {
                    modelField(data, Drawable::class.java) as? Drawable
                } else {
                    null
                }
                val payload = if (isDrawableModel) iconPayload(data) else null

                // 官方模型里的图标载荷/图标字段：任何模块都记录，供直接落图路径排除图标
                payload?.let { drawableField(it)?.let { d -> rememberIcon(d) } }
                icon?.let { rememberIcon(it) }

                // 只有主图模块才配决定胶囊背景；头部图标/装饰模块只记录图标身份
                if (!isMainPicture) return@after
                // 其它流体云（非媒体音乐服务）不碰背景
                if (key != null && !isMusicKey(key)) return@after
                if (art != null && capsule == null) {
                    preCapsulePictureSeq++
                }

                // 官方语义：
                //   位图模型 + BITMAP            -> 真封面
                //   位图模型 + RESOURCE/RES_URI  -> 应用图标（无专辑图）
                //   Drawable 模型 + 图标载荷     -> 无专辑图
                //   null / 未知模型              -> 绑定中间态，保持现状
                val hasArtAlready = capsule != null &&
                    (artByCapsule[capsule] != null || carryArtFor(key) != null)
                val resourceIcon = isBitmapModel && art != null && sourceIsResourceIcon(data)
                val iconSignal = payload != null || (isDrawableModel && icon != null)
                // 官方换封面时会先落一次图标（Drawable + icon），随后位图模型才到。
                // 已经有玻璃时这次图标只是过渡，保持原封面，避免黑底闪烁。
                val noArt = resourceIcon || (iconSignal && !hasArtAlready)
                if (noArt) {
                    if (capsule != null) {
                        markCapsuleNoArt(capsule)
                        refreshCapsule(capsule)
                    }
                    return@after
                }
                if (art != null) {
                    if (capsule != null) {
                        setCapsuleArt(capsule, art)
                        refreshCapsule(capsule)
                    } else {
                        rememberPendingArt(lottie, art)
                    }
                } else if (capsule != null) {
                    // 官方这次还没落图：保持现状，等同一控件的位图模型
                    refreshCapsule(capsule)
                }
            }
        }
    }

    /** 位图模型自带的来源 Icon：2=RESOURCE，5=RES_URI，都是应用图标。 */
    private fun sourceIsResourceIcon(model: Any): Boolean = runCatching {
        val icon = model.javaClass.declaredFields
            .firstOrNull { it.type == Icon::class.java }
            ?.apply { isAccessible = true }
            ?.get(model) as? Icon
        icon?.type == 2 || icon?.type == 5
    }.getOrDefault(false)

    private fun modelField(model: Any, type: Class<*>): Any? = runCatching {
        model.javaClass.declaredFields
            .firstOrNull { type.isAssignableFrom(it.type) }
            ?.apply { isAccessible = true }
            ?.get(model)
    }.getOrNull()

    /**
     * 官方封面解析：bundle["picture"] -> Pair(数据模型, 位图模型)。
     * 取不到 picture 时返回 null，是官方唯一的"有没有封面"判定。
     * 这里把结论同时记到解析参数与官方 uniqueId 上，后续按胶囊 key 精确查询。
     */
    private fun hookPictureResolver(method: Method) {
        method.hook {
            after {
                val pair = result<Any?>()
                val model = pair?.let {
                    runCatching { it.javaClass.getMethod("getSecond").invoke(it) }.getOrNull()
                }
                val bitmap = model?.let {
                    modelField(it, Bitmap::class.java) as? Bitmap
                }
                if (bitmap != null && !bitmap.isRecycled) rememberOfficialPicture(bitmap)
            }
        }
    }

    private fun rememberOfficialPicture(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        officialPictures[bitmap] = true
        val signature = albumSignature(bitmap)
        synchronized(officialPictureSignatures) {
            officialPictureSignatures[signature] = System.identityHashCode(bitmap).toLong()
            if (officialPictureSignatures.size > MAX_OFFICIAL_PICTURES) {
                val iterator = officialPictureSignatures.keys.iterator()
                while (officialPictureSignatures.size > MAX_OFFICIAL_PICTURES / 2 &&
                    iterator.hasNext()
                ) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
    }

    private fun isOfficialPicture(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled) return false
        if (officialPictures.containsKey(bitmap)) return true
        return synchronized(officialPictureSignatures) {
            officialPictureSignatures.containsKey(albumSignature(bitmap))
        }
    }

    private fun rememberIcon(drawable: Drawable) {
        val bitmap = (drawable as? BitmapDrawable)?.bitmap
        if (bitmap != null && !bitmap.isRecycled) {
            val signature = albumSignature(bitmap)
            synchronized(iconSignatures) {
                iconSignatures.add(signature)
                if (iconSignatures.size > 128) {
                    val iterator = iconSignatures.iterator()
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
            }
        }
    }

    private fun isKnownIcon(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled) return true
        val signature = albumSignature(bitmap)
        return synchronized(iconSignatures) { iconSignatures.contains(signature) }
    }

    /**
     * 图片模型的图标载荷方法（名称随 OTA 变化）。
     * 返回非空表示官方这次画的是应用图标 / 图标动画，而不是封面。
     */
    private fun iconPayload(model: Any): Any? {
        val type = model.javaClass
        val method = iconPayloadMethodByClass[type] ?: runCatching {
            type.methods.firstOrNull {
                it.parameterCount == 0 && it.returnType.name == ICON_PAYLOAD_TYPE
            }
        }.getOrNull()?.also { iconPayloadMethodByClass[type] = it } ?: return null
        return runCatching { method.invoke(model) }.getOrNull()
    }

    /** 从图标载荷里取出它真正要画的 Drawable（字段可能包一层）。 */
    private fun drawableField(payload: Any): Drawable? {
        (modelField(payload, Drawable::class.java) as? Drawable)?.let { return it }
        payload.javaClass.declaredFields.forEach { field ->
            val value = runCatching {
                field.isAccessible = true
                field.get(payload)
            }.getOrNull()
            if (value != null && value !== payload) {
                (modelField(value, Drawable::class.java) as? Drawable)?.let { return it }
            }
        }
        return null
    }

    /** 官方每帧布局胶囊 drawable 的入口，真正绘制的 CapsuleView 必经。 */
    private fun hookCapsuleViewDraw(method: Method) {
        method.hook {
            after {
                (instance as? View)?.let { refreshCapsule(it) }
            }
        }
    }

    /** 绘制兜底：官方复用内容视图、迁移 CapsuleView、重置背景时在这一帧纠正。 */
    private fun hookCapsuleViewOnDraw(method: Method) {
        method.hook {
            after {
                (instance as? View)?.let { refreshCapsule(it) }
            }
        }
    }

    /** 挂载入口：胶囊进窗口时先贴图，赶在首帧绘制前。 */
    private fun hookCapsuleViewAttach(method: Method) {
        method.hook {
            after {
                (instance as? View)?.let { refreshCapsule(it) }
            }
        }
    }

    /**
     * 官方切换/抢占入口：每次切歌、切应用、列表增删都会重建胶囊列表并重绑 viewItem。
     * 在官方完成重绑后刷新胶囊，key 变化即作废旧会话状态。
     */
    private fun hookCapsuleContainerUiState(method: Method) {
        method.hook {
            before {
                (instance as? View)?.let { captureCarryArt(it) }
            }
            after {
                val hasCapsules = (instance as? View)
                    ?.let { refreshCapsulesUnder(it) } == true
                if (hasCapsules) {
                    carryArtByKey.clear()
                    carryPictureSeq = -1L
                }
            }
        }
    }

    /** 官方可见标记：setHidden(false) 的就是当前胶囊，切换应用时立刻变化。 */
    private fun hookCapsuleViewHidden(method: Method) {
        method.hook {
            after {
                val view = instance as? View ?: return@after
                val hidden = args.getOrNull(0) as? Boolean ?: return@after
                if (!hidden) refreshCapsule(view)
            }
        }
    }

    /**
     * 官方缩回/展开入口：缩回态用独立 mini CapsuleView，展开后回到主胶囊。
     * 两边各自按官方模型贴图。
     */
    private fun hookCapsuleMiniViewShown(method: Method) {
        method.hook {
            after {
                (instance as? View)?.let { refreshCapsulesUnder(it) }
            }
        }
    }

    private fun refreshCapsulesUnder(root: View): Boolean {
        val capsules = mutableListOf<View>()
        collectCapsuleViews(root, capsules, 0)
        for (capsule in capsules) {
            val sessionView = sessionViewOf(capsule)
            val oldSessionView = sessionViewByCapsule[capsule]
            if (oldSessionView != null && sessionView != null && oldSessionView !== sessionView) {
                // 官方把同一个 CapsuleView 换绑到另一条会话：旧会话图状态作废，
                // 背景保持到新会话的位图模型落下，避免切换瞬间黑底。
                clearCapsuleState(capsule)
            }
            if (sessionView != null) sessionViewByCapsule[capsule] = sessionView
            refreshCapsule(capsule)
        }
        return capsules.isNotEmpty()
    }

    private fun collectCapsuleViews(view: View, out: MutableList<View>, depth: Int) {
        if (depth > 12 || out.size >= 32) return
        if (view.javaClass.name.endsWith("CapsuleView")) {
            out.add(view)
            return
        }
        if (view !is ViewGroup) return
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i) ?: continue
            collectCapsuleViews(child, out, depth + 1)
        }
    }

    /**
     * 图片控件的两个官方落点：CustomLottieView.setImageDrawable 与 Lottie 基类
     * setImageBitmap（CapsulePage 直接绑定）。两者共用同一套封面判定；
     * 清空/未知位图只是官方中间态，一律忽略，避免黑底。
     */
    private fun hookLottieDrawable(method: Method) {
        method.hook {
            after {
                val lottie = instance as? View ?: return@after
                val capsule = findParentCapsuleView(lottie)
                capsule?.let { registerLottie(it, lottie) }
                val drawable = args.getOrNull(0) as? Drawable
                if (drawable == null) return@after
                val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return@after
                consumeLottieImage(lottie, bitmap)
            }
        }
    }

    /** CapsulePage 会直接调用 Lottie 基类的 setImageBitmap；只接受 CustomLottieView 实例。 */
    private fun hookLottieBitmap(method: Method) {
        method.hook {
            after {
                val lottie = instance as? View ?: return@after
                if (lottie.javaClass.name != CUSTOM_LOTTIE_VIEW) return@after
                val bitmap = args.getOrNull(0) as? Bitmap ?: return@after
                consumeLottieImage(lottie, bitmap)
            }
        }
    }

    private fun consumeLottieImage(lottie: View, bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        val capsule = findParentCapsuleView(lottie)
        capsule?.let { registerLottie(it, lottie) }
        val official = isOfficialPicture(bitmap)
        val knownIcon = isKnownIcon(bitmap)
        val key = capsule?.let { capsuleKeyOf(it) }
        if (knownIcon) return
        if (key != null && !isMusicKey(key)) return
        if (capsule == null) {
            // 图片控件还没挂到胶囊：官方封面先暂存，挂载时按 key 认领
            if (official) rememberPendingArt(lottie, bitmap)
            return
        }
        val hasOfficialArt = artByCapsule[capsule] != null
        if (!official && !hasOfficialArt) {
            // 首帧未知位图：等官方位图/Drawable 模型，它随后一定会到。
            return
        }

        // hasPicture=true 时，图片控件里的非图标位图就是官方封面
        // （包括缩回态缩略图）；已有官方封面时允许同会话缩略图刷新底图。
        setCapsuleArt(capsule, bitmap)
        refreshCapsule(capsule)
    }

    /**
     * 以当前真正绘制的胶囊为准，状态只来自官方：
     *   picture 存在 + 位图模型 -> 贴玻璃
     *   picture 不存在 / 图标 / 空白模型 -> 原生背景
     *   官方还没给结论 -> 保持现状，避免闪烁
     */
    private fun refreshCapsule(capsule: View) {
        val key = capsuleKeyOf(capsule)
        if (key != null && !isMusicKey(key)) {
            clearCapsuleState(capsule)
            restoreBackground(capsule)
            return
        }
        if (artByCapsule[capsule] == null) {
            carryArtFor(key)?.let { art ->
                setCapsuleArt(capsule, art)
            }
        }
        adoptPending(capsule)
        val art = artByCapsule[capsule]
        if (art != null && !art.isRecycled) {
            if (glassSources[capsule] === art &&
                capsule.background === glassDrawables[capsule]
            ) {
                return
            }
            applyAlbum(capsule, art)
            return
        }
        if (noArtByCapsule[capsule] == true) {
            restoreBackground(capsule)
        }
    }

    private fun applyAlbum(target: View, bitmap: Bitmap) {
        val prepared = prepareAlbum(bitmap)
        if (!prepared.usable()) return
        if (!originalBackgrounds.containsKey(target)) {
            originalBackgrounds[target] = target.background
        }
        val previous = target.background
        val glass = glassDrawables[target]
        if (glass == null) {
            GlassDrawable(prepared).also {
                glassDrawables[target] = it
                glassSources[target] = bitmap
                target.background = it
                if (previous != null) it.bounds = Rect(previous.bounds)
            }
        } else {
            glass.setAlbum(prepared)
            glassSources[target] = bitmap
            if (previous !== glass) {
                target.background = glass
                if (previous != null) glass.bounds = Rect(previous.bounds)
            }
        }
    }

    private fun restoreBackground(view: View) {
        val glass = glassDrawables.remove(view) ?: return
        glassSources.remove(view)
        if (view.background === glass) {
            view.background = originalBackgrounds[view]
        }
    }

    private fun prepareAlbum(bitmap: Bitmap): PreparedAlbum {
        val cached = lastPreparedAlbum
        if (cached != null && cached.usable() &&
            (lastAlbumBitmap === bitmap || cached.signature == albumSignature(bitmap))
        ) return cached
        return PreparedAlbum(bitmap, albumSignature(bitmap)).also {
            lastPreparedAlbum = it
            lastAlbumBitmap = bitmap
        }
    }

    private fun albumSignature(bitmap: Bitmap): Long {
        if (bitmap.isRecycled) return 0L
        signatureByBitmap[bitmap]?.let { return it }
        val signature = runCatching {
            val software = if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    ?: return@runCatching -System.identityHashCode(bitmap).toLong() - 1L
            } else {
                bitmap
            }
            val sampleW = 24
            val sampleH = 24
            val scaled = Bitmap.createScaledBitmap(software, sampleW, sampleH, true)
            val pixels = IntArray(sampleW * sampleH)
            scaled.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)
            if (scaled !== software && scaled !== bitmap) scaled.recycle()
            if (software !== bitmap) software.recycle()
            var hash = 1469598103934665603L
            for (pixel in pixels) {
                hash = (hash * 1099511628211L) xor pixel.toLong()
            }
            hash
        }.getOrDefault(-System.identityHashCode(bitmap).toLong() - 1L)
        signatureByBitmap[bitmap] = signature
        return signature
    }

    private fun capsuleKeyOf(view: View): String? {
        val item = viewItemOf(view) ?: return keyByCapsule[view]
        if (itemByCapsule[view] !== item) {
            itemByCapsule[view] = item
            readKeyOfItem(item)?.let { keyByCapsule[view] = it }
        }
        return keyByCapsule[view]
    }

    private fun viewItemOf(view: View): Any? {
        val method = viewItemMethod ?: runCatching {
            view.javaClass.getMethod("getViewItem")
        }.getOrNull()?.also { viewItemMethod = it } ?: return null
        return runCatching { method.invoke(view) }.getOrNull()
    }

    /** 官方 viewItem 的 View 字段就是这条会话的内容视图，字段名随 OTA 变化。 */
    private fun sessionViewOf(capsule: View): View? {
        val item = viewItemOf(capsule) ?: return null
        return item.javaClass.declaredFields
            .filter { View::class.java.isAssignableFrom(it.type) }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(item) as? View
                }.getOrNull()
            }
            .firstOrNull { it !== capsule }
    }

    private fun readKeyOfItem(item: Any): String? = runCatching {
        item.javaClass.declaredFields
            .firstOrNull { it.type == String::class.java }
            ?.apply { isAccessible = true }
            ?.get(item) as? String
    }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }

    private fun registerLottie(capsule: View, lottie: View) {
        val list = lottiesByCapsule[capsule]
            ?: mutableListOf<View>().also { lottiesByCapsule[capsule] = it }
        if (list.none { it === lottie }) {
            list.add(lottie)
            if (list.size > MAX_SLOTS_PER_CAPSULE) list.removeAt(0)
        }
    }

    private fun slotsOf(capsule: View): List<View> {
        val result = mutableListOf<View>()
        lottiesByCapsule[capsule]?.forEach { if (it.parent != null) result.add(it) }
        val scanned = mutableListOf<View>()
        collectLotties(capsule, scanned, 0)
        for (lottie in scanned) {
            if (result.none { it === lottie }) result.add(lottie)
        }
        if (result.isNotEmpty()) lottiesByCapsule[capsule] = result.toMutableList()
        return result
    }

    private fun collectLotties(view: View, out: MutableList<View>, depth: Int) {
        if (depth > 12 || out.size >= MAX_SLOTS_PER_CAPSULE) return
        if (view.javaClass.name == CUSTOM_LOTTIE_VIEW) {
            out.add(view)
            return
        }
        if (view !is ViewGroup) return
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i) ?: continue
            collectLotties(child, out, depth + 1)
        }
    }

    private fun resourceEntryName(view: View): String? = runCatching {
        if (view.id == View.NO_ID) null
        else view.resources.getResourceEntryName(view.id)
    }.getOrNull()

    private fun setCapsuleArt(capsule: View, bitmap: Bitmap) {
        artByCapsule[capsule] = bitmap
        noArtByCapsule.remove(capsule)
        rememberOfficialPicture(bitmap)
    }

    private fun markCapsuleNoArt(capsule: View) {
        artByCapsule.remove(capsule)
        noArtByCapsule[capsule] = true
        capsuleKeyOf(capsule)?.let { carryArtByKey.remove(it) }
    }

    private fun clearCapsuleState(capsule: View) {
        artByCapsule.remove(capsule)
        noArtByCapsule.remove(capsule)
    }

    private fun rememberPendingArt(lottie: View, bitmap: Bitmap) {
        pendingArtByLottie[lottie] = bitmap
        pendingSeqByLottie[lottie] = ++modelSeqCounter
    }

    private fun adoptPending(capsule: View) {
        if (artByCapsule[capsule] != null || noArtByCapsule[capsule] == true) return
        val slots = slotsOf(capsule)
        if (slots.isEmpty()) return
        var best: View? = null
        var bestSeq = -1L
        for (slot in slots) {
            val seq = pendingSeqByLottie[slot] ?: continue
            if (seq > bestSeq) {
                bestSeq = seq
                best = slot
            }
        }
        val winner = best ?: return
        val art = pendingArtByLottie[winner]
        for (slot in slots) {
            pendingArtByLottie.remove(slot)
            pendingSeqByLottie.remove(slot)
        }
        if (art != null && !art.isRecycled) {
            setCapsuleArt(capsule, art)
        }
    }

    private fun findParentCapsuleView(view: View): View? {
        var current: Any? = view
        repeat(12) {
            if (current is View && current.javaClass.name.endsWith("CapsuleView")) return current
            current = (current as? View)?.parent
        }
        return null
    }

    private fun isMusicKey(key: String?): Boolean = key?.contains(MUSIC_SERVICE_ID) == true

    /**
     * 自动缩回时官方先以空 viewList 调 uiState，再以相同 key 重建胶囊。
     * 空捕获不能覆盖已有封面；捕获到新胶囊后再替换交接内容。
     */
    private fun captureCarryArt(root: View) {
        val capsules = mutableListOf<View>()
        collectCapsuleViews(root, capsules, 0)
        val captured = HashMap<String, Bitmap>()
        for (capsule in capsules) {
            val key = capsuleKeyOf(capsule) ?: continue
            if (!isMusicKey(key) || captured.containsKey(key)) continue
            val art = artByCapsule[capsule]?.takeIf { !it.isRecycled } ?: continue
            captured[key] = art
        }
        if (captured.isNotEmpty()) {
            carryArtByKey.clear()
            carryArtByKey.putAll(captured)
            carryPictureSeq = preCapsulePictureSeq
        }
    }

    private fun carryArtFor(key: String?): Bitmap? {
        if (key == null || !isMusicKey(key)) return null
        if (carryPictureSeq != preCapsulePictureSeq) return null
        val art = carryArtByKey[key] ?: return null
        if (!art.isRecycled) return art
        carryArtByKey.remove(key)
        return null
    }

    /** 预渲染模糊底图 + 平均色 tint，供 GlassDrawable 复用。 */
    private class PreparedAlbum(val source: Bitmap, val signature: Long) {
        val averageTint: Int = runCatching { averageTint(source) }
            .getOrDefault(Color.argb(GLASS_AVERAGE_TINT_ALPHA, 0, 0, 0))
        val preRendered: Bitmap? = runCatching {
            val width = 350
            val height = 105
            val cropped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(cropped)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(
                source, centerCropRect(source.width, source.height, width, height),
                Rect(0, 0, width, height), paint
            )
            val normalized = suppressHighlights(cropped)
            fastBlur(normalized, GLASS_BLUR_RADIUS).also {
                if (normalized !== cropped) normalized.recycle()
            }
        }.getOrNull()

        fun usable(): Boolean = preRendered?.isRecycled == false || !source.isRecycled

        private fun averageTint(bitmap: Bitmap): Int {
            val sampleW = 18
            val sampleH = 18
            val sample = Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, true)
            var red = 0L
            var green = 0L
            var blue = 0L
            var count = 0L
            for (x in 0 until sampleW) {
                for (y in 0 until sampleH) {
                    val color = sample.getPixel(x, y)
                    if (Color.alpha(color) < 20) continue
                    red += Color.red(color)
                    green += Color.green(color)
                    blue += Color.blue(color)
                    count++
                }
            }
            if (sample !== bitmap) sample.recycle()
            if (count <= 0) return Color.argb(GLASS_AVERAGE_TINT_ALPHA, 0, 0, 0)
            val r = ((red / count).toInt() + GLASS_AVERAGE_TINT_LIGHTEN).coerceAtMost(255)
            val g = ((green / count).toInt() + GLASS_AVERAGE_TINT_LIGHTEN).coerceAtMost(255)
            val b = ((blue / count).toInt() + GLASS_AVERAGE_TINT_LIGHTEN).coerceAtMost(255)
            return Color.argb(GLASS_AVERAGE_TINT_ALPHA, r, g, b)
        }

        fun suppressHighlights(bitmap: Bitmap): Bitmap {
            val tintR = Color.red(averageTint)
            val tintG = Color.green(averageTint)
            val tintB = Color.blue(averageTint)
            val result = runCatching {
                bitmap.copy(Bitmap.Config.ARGB_8888, true)
            }.getOrNull() ?: return bitmap
            val width = result.width
            val height = result.height
            val pixels = IntArray(width * height)
            result.getPixels(pixels, 0, width, 0, 0, width, height)
            for (index in pixels.indices) {
                val color = pixels[index]
                val alpha = Color.alpha(color)
                if (alpha == 0) continue
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                val luminance = (r * 299 + g * 587 + b * 114) / 1000
                if (luminance < GLASS_HIGHLIGHT_THRESHOLD) continue
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                if (max - min <= GLASS_HIGHLIGHT_DESAT_THRESHOLD) {
                    val mixR = (r * GLASS_HIGHLIGHT_DARKEN_PERCENT + tintR *
                        (100 - GLASS_HIGHLIGHT_DARKEN_PERCENT)) / 100
                    val mixG = (g * GLASS_HIGHLIGHT_DARKEN_PERCENT + tintG *
                        (100 - GLASS_HIGHLIGHT_DARKEN_PERCENT)) / 100
                    val mixB = (b * GLASS_HIGHLIGHT_DARKEN_PERCENT + tintB *
                        (100 - GLASS_HIGHLIGHT_DARKEN_PERCENT)) / 100
                    pixels[index] = Color.argb(alpha, mixR, mixG, mixB)
                } else {
                    val scale = GLASS_HIGHLIGHT_TINT_PERCENT / 100f
                    val mixR = (r * (1 - scale) + tintR * scale).toInt()
                    val mixG = (g * (1 - scale) + tintG * scale).toInt()
                    val mixB = (b * (1 - scale) + tintB * scale).toInt()
                    pixels[index] = Color.argb(alpha, mixR, mixG, mixB)
                }
            }
            result.setPixels(pixels, 0, width, 0, 0, width, height)
            return result
        }
    }

    private class GlassDrawable(album: PreparedAlbum) : Drawable() {
        private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var prepared: PreparedAlbum = album
        private var rendered: Bitmap? = null
        private var renderedW = 0
        private var renderedH = 0

        fun setAlbum(album: PreparedAlbum) {
            if (prepared === album && rendered != null) return
            prepared = album
            rendered = null
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val width = bounds.width()
            val height = bounds.height()
            if (width <= 0 || height <= 0 || !prepared.usable()) return
            val radius = height / 2f
            val bitmap = render(width, height)
            val save = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            overlayPaint.color = Color.WHITE
            canvas.drawRoundRect(rect, radius, radius, overlayPaint)
            overlayPaint.xfermode =
                android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(bitmap, 0f, 0f, overlayPaint)
            overlayPaint.xfermode = null
            overlayPaint.color = Color.argb(GLASS_SATURATION_ALPHA, 255, 255, 255)
            canvas.drawRoundRect(rect, radius, radius, overlayPaint)
            overlayPaint.color = prepared.averageTint
            canvas.drawRoundRect(rect, radius, radius, overlayPaint)
            overlayPaint.color = Color.argb(GLASS_WHITE_ALPHA, 255, 255, 255)
            canvas.drawRoundRect(rect, radius, radius, overlayPaint)
            overlayPaint.color = Color.argb(GLASS_SOFT_WHITE_ALPHA, 245, 250, 255)
            canvas.drawRoundRect(rect, radius, radius, overlayPaint)
            overlayPaint.style = Paint.Style.STROKE
            overlayPaint.strokeWidth = 1.5f
            overlayPaint.color = Color.argb(GLASS_EDGE_LIGHT_ALPHA, 255, 255, 255)
            canvas.drawRoundRect(rect.insetCopy(0.75f), radius, radius, overlayPaint)
            overlayPaint.style = Paint.Style.FILL
            canvas.restoreToCount(layer)
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) {
            imagePaint.alpha = alpha
            overlayPaint.alpha = alpha
        }

        override fun getOutline(outline: Outline) {
            val width = bounds.width()
            val height = bounds.height()
            if (width <= 0 || height <= 0) {
                outline.setEmpty()
                return
            }
            outline.setRoundRect(
                bounds.left, bounds.top, bounds.right, bounds.bottom, height / 2f
            )
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            imagePaint.colorFilter = colorFilter
            overlayPaint.colorFilter = colorFilter
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = -1

        override fun getIntrinsicHeight(): Int = -1

        override fun getMinimumWidth(): Int = 0

        override fun getMinimumHeight(): Int = 0

        private fun render(width: Int, height: Int): Bitmap {
            val cached = rendered
            if (cached != null && renderedW == width && renderedH == height &&
                !cached.isRecycled
            ) return cached
            prepared.preRendered?.let {
                if (!it.isRecycled && width > 0 && height > 0) {
                    val scaled = Bitmap.createScaledBitmap(it, width, height, true)
                    rendered = scaled
                    renderedW = width
                    renderedH = height
                    return scaled
                }
            }
            val base = prepared.preRendered?.takeIf { !it.isRecycled }
                ?: prepared.source.takeIf { !it.isRecycled }
                ?: return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val cropped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(cropped)
            val src = centerCropRect(base.width, base.height, width, height)
            canvas.drawBitmap(base, src, Rect(0, 0, width, height), imagePaint)
            val normalized = prepared.suppressHighlights(cropped)
            val blurred = fastBlur(normalized, GLASS_BLUR_RADIUS)
            rendered = blurred
            renderedW = width
            renderedH = height
            if (normalized !== cropped) normalized.recycle()
            return blurred
        }

        private fun RectF.insetCopy(inset: Float): RectF =
            RectF(left + inset, top + inset, right - inset, bottom - inset)
    }
}
