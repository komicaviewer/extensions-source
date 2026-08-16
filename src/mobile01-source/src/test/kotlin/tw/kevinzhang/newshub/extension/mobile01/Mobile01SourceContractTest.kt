package tw.kevinzhang.newshub.extension.mobile01

import org.junit.Test
import tw.kevinzhang.newshub.extension.runtime.assertSourceDescriptorContract

class Mobile01SourceContractTest {
    @Test fun `protocol v2 descriptor fields stay stable`() = assertSourceDescriptorContract(
        Mobile01Source(), "tw.kevinzhang.mobile01", "Mobile01", "zh-TW", 3, false,
    )
}
