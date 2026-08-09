package tw.kevinzhang.newshub.extension.mobile01

import tw.kevinzhang.extension_api.IsolatedSourceService
import tw.kevinzhang.extension_api.Source

class Mobile01ExtensionService : IsolatedSourceService() {
    override val source: Source by lazy(::Mobile01Source)
}
