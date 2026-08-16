package tw.kevinzhang.newshub.extension.eyny

import org.junit.Test
import tw.kevinzhang.newshub.extension.runtime.assertSourceDescriptorContract

class EynySourceContractTest {
    @Test fun `protocol v2 descriptor fields stay stable`() = assertSourceDescriptorContract(
        EynySource(), EynySource.SOURCE_ID, "EYNY 伊莉討論區", "zh-TW", 3, false,
    )
}
