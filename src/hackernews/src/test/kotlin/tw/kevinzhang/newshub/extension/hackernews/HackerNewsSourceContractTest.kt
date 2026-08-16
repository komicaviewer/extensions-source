package tw.kevinzhang.newshub.extension.hackernews

import org.junit.Test
import tw.kevinzhang.newshub.extension.runtime.assertSourceDescriptorContract

class HackerNewsSourceContractTest {
    @Test fun `protocol v2 descriptor fields stay stable`() = assertSourceDescriptorContract(
        HackerNewsSource(), "tw.kevinzhang.newshub.extension.hackernews", "Hacker News", "en", 1, false,
    )
}
