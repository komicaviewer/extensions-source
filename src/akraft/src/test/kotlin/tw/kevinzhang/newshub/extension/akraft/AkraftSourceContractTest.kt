package tw.kevinzhang.newshub.extension.akraft

import org.junit.Test
import tw.kevinzhang.newshub.extension.runtime.assertSourceDescriptorContract

class AkraftSourceContractTest {
    @Test fun `protocol v2 descriptor fields stay stable`() = assertSourceDescriptorContract(
        AkraftSource(), "tw.kevinzhang.akraft", "Akraft", "zh-TW", 2, true,
    )
}
