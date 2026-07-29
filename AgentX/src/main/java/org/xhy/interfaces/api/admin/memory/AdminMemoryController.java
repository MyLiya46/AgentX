package org.xhy.interfaces.api.admin.memory;

import org.springframework.web.bind.annotation.*;
import org.xhy.application.memory.service.MemoryAppService;
import org.xhy.interfaces.api.common.Result;

import java.util.List;
import java.util.Map;

/** 记忆管理 — 管理员运维接口 */
@RestController
@RequestMapping("/admin/memory")
public class AdminMemoryController {

    private final MemoryAppService memoryAppService;

    public AdminMemoryController(MemoryAppService memoryAppService) {
        this.memoryAppService = memoryAppService;
    }

    /** 检测指定用户的孤儿向量记录 */
    @GetMapping("/orphans")
    public Result<Map<String, Object>> findOrphans(@RequestParam String userId) {
        List<String> orphanIds = memoryAppService.findOrphanVectors(userId);
        return Result.success(Map.of("userId", userId, "count", orphanIds.size(), "orphanItemIds", orphanIds));
    }

    /** 清理指定用户的孤儿向量记录 */
    @DeleteMapping("/orphans")
    public Result<Map<String, Object>> cleanOrphans(@RequestParam String userId) {
        int cleaned = memoryAppService.cleanOrphanVectors(userId);
        return Result.success(Map.of("userId", userId, "cleaned", cleaned));
    }
}
