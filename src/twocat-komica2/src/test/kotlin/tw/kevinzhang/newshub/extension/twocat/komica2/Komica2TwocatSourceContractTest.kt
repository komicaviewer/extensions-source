package tw.kevinzhang.newshub.extension.twocat.komica2

import org.junit.Test
import tw.kevinzhang.newshub.extension.runtime.assertSourceDescriptorContract

class Komica2TwocatSourceContractTest {
    @Test fun `protocol v2 descriptor fields stay stable`() = assertSourceDescriptorContract(
        Komica2TwocatSource(), "tw.kevinzhang.komica2.twocat", "Komica2 Twocat", "zh-TW", 4, true,
    )
}
