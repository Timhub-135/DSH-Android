package io.github.timhub135.dsh.core.capability

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.timhub135.dsh.core.capability.tools.AppTools
import io.github.timhub135.dsh.core.capability.tools.BrowserTools
import io.github.timhub135.dsh.core.capability.tools.ClipboardTools
import io.github.timhub135.dsh.core.capability.tools.CommsTools
import io.github.timhub135.dsh.core.capability.tools.FileTools
import io.github.timhub135.dsh.core.capability.tools.KnowledgeTools
import io.github.timhub135.dsh.core.capability.tools.MediaTools
import io.github.timhub135.dsh.core.capability.tools.NotificationTools
import io.github.timhub135.dsh.core.capability.tools.RuleTools
import io.github.timhub135.dsh.core.capability.tools.ScreenTools
import io.github.timhub135.dsh.core.capability.tools.SystemTools
import io.github.timhub135.dsh.core.engine.RpcJson
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.io.File

/**
 * The model-facing `capability/list` (every tool the app can offer). Every byte here is input
 * tokens on every request of a conversation that has the tool loaded, so the descriptors are kept
 * within a budget. With `DSH_CAPABILITY_DUMP=<file>` the list is also written out (the engine's
 * token measurements replay it through a fake bridge).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class CapabilityListTest {
    /** Builds a ToolSet without its collaborators (allocated, never constructed): descriptors only need the constructor to run. */
    private fun <T : ToolSet> build(type: Class<T>): T {
        val ctor = type.constructors.single()
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        val args = ctor.parameterTypes.map { p ->
            when {
                p == Context::class.java -> ApplicationProvider.getApplicationContext<Context>()
                p.isInterface -> java.lang.reflect.Proxy.newProxyInstance(p.classLoader, arrayOf(p)) { _, _, _ -> error("not used") }
                else -> allocate.invoke(unsafe, p)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(*args.toTypedArray()) as T
    }

    private fun listed(): List<ToolDescriptor> = listOf(
        FileTools::class.java, AppTools::class.java, ScreenTools::class.java, SystemTools::class.java, NotificationTools::class.java,
        CommsTools::class.java, MediaTools::class.java, ClipboardTools::class.java, BrowserTools::class.java, RuleTools::class.java,
        KnowledgeTools::class.java,
    ).flatMap { build(it).tools }.map { CapabilityHub.listed(it) }

    @Test fun `descriptors stay within the token budget`() {
        val tools = listed()
        val json = JsonArray(tools.map { RpcJson.encodeToJsonElement(ToolDescriptor.serializer(), it) }).toString()
        System.getenv("DSH_CAPABILITY_DUMP")?.takeIf { it.isNotBlank() }?.let { File(it).writeText(json) }
        assertTrue("no tools", tools.isNotEmpty())
        tools.forEach { t ->
            val size = RpcJson.encodeToJsonElement(ToolDescriptor.serializer(), t).toString().length
            assertTrue("${t.name}: descriptor is $size chars (budget $PER_TOOL)", size <= PER_TOOL)
        }
        assertTrue("capability/list is ${json.length} chars (budget $TOTAL)", json.length <= TOTAL)
    }

    companion object {
        /** rule_create (the RuleSpec reference) is the largest at ~2.1k characters. */
        const val PER_TOOL = 2_200
        /** All ~67 tools, as JSON (descriptions are most of it): ~40k characters ≈ 11k tokens. */
        const val TOTAL = 42_000
    }
}
