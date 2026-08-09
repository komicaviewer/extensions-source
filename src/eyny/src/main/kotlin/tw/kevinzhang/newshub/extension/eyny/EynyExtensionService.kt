package tw.kevinzhang.newshub.extension.eyny

import tw.kevinzhang.extension_api.IsolatedSourceService
import tw.kevinzhang.extension_api.Source

class EynyExtensionService : IsolatedSourceService() {
    override val source: Source by lazy(::EynySource)
}
