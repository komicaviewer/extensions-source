package tw.kevinzhang.newshub.extension.sora.komica2

import org.junit.Test
import tw.kevinzhang.newshub.extension.runtime.assertSourceDescriptorContract

class Komica2SoraSourceContractTest {
    @Test fun `protocol v2 descriptor fields stay stable`() = assertSourceDescriptorContract(
        Komica2SoraSource(), "tw.kevinzhang.komica2.sora", "Komica2 Sora", "zh-TW", 4, true,
    )
}
