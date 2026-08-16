package tw.kevinzhang.newshub.extension.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import tw.kevinzhang.extension_api.Source

/** Locks every value that protocol v2 exposes through the runtime descriptor. */
fun assertSourceDescriptorContract(
    source: Source,
    id: String,
    name: String,
    language: String,
    version: Int,
    alwaysUseRawImage: Boolean,
) {
    assertEquals(id, source.id)
    assertEquals(name, source.name)
    assertEquals(language, source.language)
    assertEquals(version, source.version)
    assertNotNull(source.iconUrl)
    assertFalse(source.supportsCommentPagination)
    assertEquals(alwaysUseRawImage, source.alwaysUseRawImage)
    assertFalse(source.needsLogin)
}
