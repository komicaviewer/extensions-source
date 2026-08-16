package tw.kevinzhang.newshub.extension.wtako

import org.junit.Test
import tw.kevinzhang.newshub.extension.runtime.assertSourceDescriptorContract

class WtakoSourceContractTest {
    @Test fun `protocol v2 descriptor fields stay stable`() = assertSourceDescriptorContract(
        WtakoSource(), "tw.kevinzhang.wtako", "Wtako", "zh-TW", 3, true,
    )
}
