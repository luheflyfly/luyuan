package com.luyuan.data

/** 贴纸目录：Iconify SVG（Fluent 3D + OpenMoji），MIT / CC BY-SA 4.0 许可 */
object StickerCatalog {
    data class Pack(val id: String, val label: String, val stickers: List<String>)

    // vc103：去重——fluent-emoji 包内 "cat-face" 曾出现两次，LazyVerticalGrid key 撞车直接崩
    val packs = listOf(
        Pack("fluent-emoji", "Fluent 3D", listOf(
            "angry-face", "angry-face-with-horns", "anguished-face", "anxious-face-with-sweat",
            "astonished-face", "beaming-face-with-smiling-eyes", "cat-face", "clown-face",
            "cold-face", "confounded-face", "confused-face", "cow-face",
            "cowboy-hat-face", "crying-face", "disappointed-face", "disguised-face",
            "dog-face", "dotted-line-face", "downcast-face-with-sweat", "dragon-face",
            "drooling-face", "expressionless-face", "face-blowing-a-kiss", "face-exhaling",
            "face-holding-back-tears", "face-in-clouds", "face-savoring-food", "face-screaming-in-fear",
            "face-with-diagonal-mouth", "face-with-hand-over-mouth", "bear", "black-cat",
            "cat", "cat-face", "cat-with-tears-of-joy", "cat-with-wry-smile",
            "cow", "cow-face", "cowboy-hat-face", "crying-cat",
            "dog", "dog-face", "fox", "frog",
            "grinning-cat", "grinning-cat-with-smiling-eyes", "guide-dog", "hear-no-evil-monkey",
            "hot-dog", "identification-card", "japanese-application-button", "kissing-cat",
            "koala", "man-beard", "anatomical-heart", "avocado",
            "baby-bottle", "baguette-bread", "balloon", "banana",
            "bat", "bathtub", "battery", "beating-heart",
            "beer-mug", "beetle", "bird", "birthday-cake",
            "black-heart", "blackbird", "blossom", "blowfish",
            "blue-heart", "bottle-with-popping-cork", "bowl-with-spoon", "bowling",
            "bread", "broken-heart", "brown-heart", "bubble-tea",
            "butter", "butterfly", "camel", "candy",
            "capricorn", "carousel-horse", "carrot", "cheese-wedge",
        )),
        Pack("openmoji", "OpenMoji", listOf(
            "cat-face", "cow-face", "dog-face", "dragon-face",
            "horse-face", "monkey-face", "mouse-face", "pig-face",
            "rabbit-face", "tiger-face", "annoyed-face-with-tongue", "dejected-face",
            "disinfect-surface", "exhausted-face", "facebook", "facetime",
            "incredulous-face", "man-facepalming", "man-facepalming-dark-skin-tone", "man-facepalming-light-skin-tone",
            "man-facepalming-medium-dark-skin-tone", "man-facepalming-medium-light-skin-tone", "man-facepalming-medium-skin-tone", "person-facepalming",
            "person-facepalming-dark-skin-tone", "person-facepalming-light-skin-tone", "person-facepalming-medium-dark-skin-tone", "person-facepalming-medium-light-skin-tone",
            "person-facepalming-medium-skin-tone", "woman-facepalming", "teddy-bear", "bear",
            "black-cat", "cat", "cat-face", "cow",
            "cow-face", "dog", "dog-face", "fox",
            "frog", "guide-dog", "koala", "monkey",
            "monkey-face", "panda", "penguin", "pig",
            "pig-face", "pig-nose", "polar-bear", "rabbit",
            "rabbit-face", "service-dog", "balloon", "bowling",
            "firecracker", "fireworks", "fishing-pole", "heart-suit",
            "moon-viewing-ceremony", "party-popper", "sparkler", "sparkles",
            "tanabata-tree", "wrapped-gift", "bat", "beetle",
            "bird", "black-bird", "blossom", "blowfish",
            "butterfly", "camel", "cherry-blossom", "chicken",
            "crab", "deer", "dolphin", "duck",
        )),
    ).map { p -> p.copy(stickers = p.stickers.distinct()) }
}
