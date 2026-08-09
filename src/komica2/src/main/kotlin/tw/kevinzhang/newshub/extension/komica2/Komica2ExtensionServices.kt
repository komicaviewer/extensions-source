package tw.kevinzhang.newshub.extension.komica2

import tw.kevinzhang.extension_api.IsolatedSourceService
import tw.kevinzhang.extension_api.Source
import tw.kevinzhang.newshub.extension.sora.komica2.Komica2SoraSource
import tw.kevinzhang.newshub.extension.twocat.komica2.Komica2TwocatSource
import tw.kevinzhang.newshub.extension.zawarudo.komica2.Komica2ZawarudoSource

class TwocatExtensionService : IsolatedSourceService() {
    override val source: Source by lazy(::Komica2TwocatSource)
}

class SoraExtensionService : IsolatedSourceService() {
    override val source: Source by lazy(::Komica2SoraSource)
}

class ZawarudoExtensionService : IsolatedSourceService() {
    override val source: Source by lazy(::Komica2ZawarudoSource)
}
