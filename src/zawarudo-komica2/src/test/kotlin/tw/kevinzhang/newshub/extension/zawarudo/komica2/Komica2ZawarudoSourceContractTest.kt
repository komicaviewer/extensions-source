package tw.kevinzhang.newshub.extension.zawarudo.komica2

import org.junit.Test
import tw.kevinzhang.newshub.extension.runtime.assertSourceDescriptorContract

class Komica2ZawarudoSourceContractTest {
    @Test fun `protocol v2 descriptor fields stay stable`() = assertSourceDescriptorContract(
        Komica2ZawarudoSource(), "tw.kevinzhang.komica2.zawarudo", "Komica2 Zawarudo", "zh-TW", 3, true,
    )
}
