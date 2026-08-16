package tw.kevinzhang.newshub.extension.sora.komica

import org.junit.Test
import tw.kevinzhang.newshub.extension.runtime.assertSourceDescriptorContract

class SoraSourceContractTest {
    @Test fun `protocol v2 descriptor fields stay stable`() = assertSourceDescriptorContract(
        SoraSource(), "tw.kevinzhang.komica.sora", "Sora", "zh-TW", 5, true,
    )
}
