package tw.kevinzhang.gamer_api.interactor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.kevinzhang.gamer_api.model.GBoard

class GetAllBoards {
    suspend fun invoke() = withContext(Dispatchers.IO) {
        listOf(
            GBoard("幻獸帕魯", "https://forum.gamer.com.tw/B.php?bsn=71458"),
            GBoard("新楓之谷", "https://forum.gamer.com.tw/B.php?bsn=7650"),
            GBoard("鳴潮", "https://forum.gamer.com.tw/B.php?bsn=74934"),
            GBoard("SpiritVale", "https://forum.gamer.com.tw/B.php?bsn=85861"),
            GBoard("碧藍幻想 系列", "https://forum.gamer.com.tw/B.php?bsn=25204"),
            GBoard("電腦應用綜合討論", "https://forum.gamer.com.tw/B.php?bsn=60030"),
            GBoard("MapleStory Worlds", "https://forum.gamer.com.tw/B.php?bsn=79354"),
            GBoard("明日方舟：終末地", "https://forum.gamer.com.tw/B.php?bsn=74604"),
            GBoard("英雄聯盟 League of Legends", "https://forum.gamer.com.tw/B.php?bsn=17532"),
            GBoard("勝利女神：妮姬", "https://forum.gamer.com.tw/B.php?bsn=36390"),
            GBoard("絕區零", "https://forum.gamer.com.tw/B.php?bsn=74860"),
            GBoard("貓咪大戰爭（にゃんこ大戦争）", "https://forum.gamer.com.tw/B.php?bsn=23772"),
            GBoard("SD 鋼彈 G 世代 永恆", "https://forum.gamer.com.tw/B.php?bsn=74906"),
            GBoard("神魔之塔", "https://forum.gamer.com.tw/B.php?bsn=23805"),
            GBoard("天堂：經典版", "https://forum.gamer.com.tw/B.php?bsn=84452"),
            GBoard("棕色塵埃 2", "https://forum.gamer.com.tw/B.php?bsn=76207"),
            GBoard("傳說對決 Arena of Valor", "https://forum.gamer.com.tw/B.php?bsn=30518"),
            GBoard("異環", "https://forum.gamer.com.tw/B.php?bsn=80679"),
            GBoard("流亡黯道 Path of Exile", "https://forum.gamer.com.tw/B.php?bsn=18966"),
            GBoard("Final Fantasy XIV", "https://forum.gamer.com.tw/B.php?bsn=17608"),
            GBoard("Steam 綜合討論板", "https://forum.gamer.com.tw/B.php?bsn=60599"),
            GBoard("崩壞：星穹鐵道", "https://forum.gamer.com.tw/B.php?bsn=72822"),
            GBoard("暗黑破壞神 4", "https://forum.gamer.com.tw/B.php?bsn=75105"),
            GBoard("天堂 Mobile", "https://forum.gamer.com.tw/B.php?bsn=25908"),
            GBoard("Pokemon GO", "https://forum.gamer.com.tw/B.php?bsn=29659"),
            GBoard("原神", "https://forum.gamer.com.tw/B.php?bsn=36730"),
            GBoard("燕雲十六聲", "https://forum.gamer.com.tw/B.php?bsn=75703"),
            GBoard("Fate/Grand Order", "https://forum.gamer.com.tw/B.php?bsn=26742"),
            GBoard("RO 仙境傳說 Online", "https://forum.gamer.com.tw/B.php?bsn=4212"),
            GBoard("蔚藍檔案", "https://forum.gamer.com.tw/B.php?bsn=38898"),
        )
    }
}
