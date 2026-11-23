package com.tianercode.opencode.runtime.local
























internal object ApkRepositoryDefaults {

    
    val REPOSITORIES = listOf("main", "community")

    
    val MIRROR_BASES =
        listOf(
            "https://mirrors.ustc.edu.cn/alpine",
            "https://mirror.nju.edu.cn/alpine",
            "https://mirrors.huaweicloud.com/alpine",
            "https://mirrors.cloud.tencent.com/alpine",
            "https://mirrors.aliyun.com/alpine",
            
            
            "https://mirrors.tuna.tsinghua.edu.cn/alpine",
            "https://mirrors.bfsu.edu.cn/alpine",
            "https://dl-cdn.alpinelinux.org/alpine",
        )
}
