package isomeria.aichat.feature.memory

/** 截图用的示例数据 */
internal val previewMemoryState = MemoryListState(
    loaded = true,
    core = MemoryRow(3, "9月2日 – 10月19日", "对方是大三学生，学设计，周末在咖啡店打工。\n喜欢猫，不太吃辣。\n约好期末考完一起去看海。", edited = false),
    rows = listOf(
        MemoryRow(2, "10月28日 – 11月1日", "对方最近在准备考试，压力有点大，约好考完一起去看海。喜欢喝不加糖的拿铁。", edited = true),
        MemoryRow(1, "10月20日 – 10月27日", "对方养了一只叫团子的橘猫，常在晚上聊天。提到过想学吉他。", edited = false),
    ),
    backlog = 128,
)
