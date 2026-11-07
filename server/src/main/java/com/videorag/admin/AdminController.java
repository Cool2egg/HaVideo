package com.videorag.admin;

import com.videorag.admin.dto.UsageView;
import com.videorag.auth.AuthService;
import com.videorag.common.Result;
import com.videorag.media.dto.FailedTaskView;
import com.videorag.media.dto.TaskView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/tasks")
    public Result<List<TaskView>> tasks(
            @RequestParam(required = false) String status,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(adminService.tasks(userId, status));
    }

    @GetMapping("/failed")
    public Result<List<FailedTaskView>> failed(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(adminService.failedTasks(userId));
    }

    @PostMapping("/tasks/{id}/requeue")
    public Result<Void> requeue(@PathVariable Long id,
                                @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        adminService.requeue(userId, id);
        return Result.ok();
    }

    @GetMapping("/usage")
    public Result<List<UsageView>> usage(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(adminService.usage(userId));
    }
}
