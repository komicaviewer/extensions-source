package tw.kevinzhang.newshub.extension.ptt

import tw.kevinzhang.extension_api.IsolatedSourceService
import tw.kevinzhang.extension_api.Source

class PttExtensionService : IsolatedSourceService() {
    override val source: Source by lazy(::PttSource)
}
