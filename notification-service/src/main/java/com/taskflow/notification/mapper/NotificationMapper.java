package com.taskflow.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.notification.entity.Notification;
import org.apache.ibatis.annotations.Mapper;

/** 站内消息 Mapper */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}
