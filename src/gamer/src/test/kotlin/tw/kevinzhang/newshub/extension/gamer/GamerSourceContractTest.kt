package tw.kevinzhang.newshub.extension.gamer

import org.junit.Test
import tw.kevinzhang.newshub.extension.runtime.assertSourceDescriptorContract

class GamerSourceContractTest {
    @Test fun `protocol v2 descriptor fields stay stable`() = assertSourceDescriptorContract(
        GamerSource(), "tw.kevinzhang.newshub.extension.gamer", "Gamer 巴哈姆特", "zh-TW", 6, false,
    )
}
