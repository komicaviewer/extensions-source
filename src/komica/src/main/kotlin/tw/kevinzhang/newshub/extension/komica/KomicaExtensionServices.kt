package tw.kevinzhang.newshub.extension.komica

import tw.kevinzhang.extension_api.IsolatedSourceService
import tw.kevinzhang.extension_api.Source
import tw.kevinzhang.newshub.extension.akraft.AkraftSource
import tw.kevinzhang.newshub.extension.nagatoyuki.NagatoyukiSource
import tw.kevinzhang.newshub.extension.sora.komica.SoraSource
import tw.kevinzhang.newshub.extension.twocat.komica.TwocatSource
import tw.kevinzhang.newshub.extension.wtako.WtakoSource

class TwocatExtensionService : IsolatedSourceService() {
    override val source: Source by lazy(::TwocatSource)
}

class SoraExtensionService : IsolatedSourceService() {
    override val source: Source by lazy(::SoraSource)
}

class AkraftExtensionService : IsolatedSourceService() {
    override val source: Source by lazy(::AkraftSource)
}

class NagatoyukiExtensionService : IsolatedSourceService() {
    override val source: Source by lazy(::NagatoyukiSource)
}

class WtakoExtensionService : IsolatedSourceService() {
    override val source: Source by lazy(::WtakoSource)
}
