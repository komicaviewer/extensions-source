package tw.kevinzhang.newshub.extension.nagatoyuki

import org.junit.Test
import tw.kevinzhang.newshub.extension.runtime.assertSourceDescriptorContract

class NagatoyukiSourceContractTest {
    @Test fun `protocol v2 descriptor fields stay stable`() = assertSourceDescriptorContract(
        NagatoyukiSource(), "tw.kevinzhang.nagatoyuki", "Nagatoyuki", "zh-TW", 3, true,
    )
}
