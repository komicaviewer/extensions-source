package tw.kevinzhang.extension.gamer

import tw.kevinzhang.extension_api.Source
import tw.kevinzhang.extension_api.model.Board
import tw.kevinzhang.extension_api.model.CommentPage
import tw.kevinzhang.extension_api.model.Post
import tw.kevinzhang.extension_api.model.Thread
import tw.kevinzhang.extension_api.model.ThreadSummary

// TODO: migrate full implementation from NewsHub/extensions-builtin/gamer/
class GamerSource : Source {
    override val id: String = "tw.kevinzhang.gamer"
    override val name: String = "Gamer 巴哈姆特"
    override val language: String = "zh-TW"
    override val version: Int = 1
    override val iconUrl: String? = null
    override val supportsCommentPagination: Boolean = false
    override val alwaysUseRawImage: Boolean = false
    override val requiresLogin: Boolean = true
    override val loginUrl: String = "https://passport.bahamut.com.tw/login.php"
    override val loginPageLoadJs: String = "User.Login.requireLoginIframe();"

    override suspend fun getBoards(): List<Board> = TODO("migrate from extensions-builtin")
    override suspend fun getThreadSummaries(board: Board, page: Int): List<ThreadSummary> = TODO("migrate from extensions-builtin")
    override suspend fun getThread(summary: ThreadSummary): Thread = TODO("migrate from extensions-builtin")
    override suspend fun getComments(post: Post, page: Int): CommentPage = TODO("migrate from extensions-builtin")
}
