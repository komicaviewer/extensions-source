package tw.kevinzhang.newshub.extension.gamer

import tw.kevinzhang.extension_api.IsolatedSourceService
import tw.kevinzhang.extension_api.Source

class GamerExtensionService : IsolatedSourceService() {
    override val source: Source by lazy(::GamerSource)
}
