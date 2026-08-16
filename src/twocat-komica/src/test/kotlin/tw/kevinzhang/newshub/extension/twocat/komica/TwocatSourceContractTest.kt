package tw.kevinzhang.newshub.extension.twocat.komica

import org.junit.Test
import tw.kevinzhang.newshub.extension.runtime.assertSourceDescriptorContract

class TwocatSourceContractTest {
    @Test fun `protocol v2 descriptor fields stay stable`() = assertSourceDescriptorContract(
        TwocatSource(), "tw.kevinzhang.komica.twocat", "Twocat", "zh-TW", 3, true,
    )
}
