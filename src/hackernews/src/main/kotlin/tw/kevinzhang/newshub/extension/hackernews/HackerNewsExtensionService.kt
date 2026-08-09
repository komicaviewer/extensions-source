package tw.kevinzhang.newshub.extension.hackernews

import tw.kevinzhang.extension_api.IsolatedSourceService
import tw.kevinzhang.extension_api.Source

class HackerNewsExtensionService : IsolatedSourceService() {
    override val source: Source by lazy(::HackerNewsSource)
}
