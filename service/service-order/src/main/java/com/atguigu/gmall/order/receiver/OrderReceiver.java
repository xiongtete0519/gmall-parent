package com.atguigu.gmall.order.receiver;

import com.atguigu.gmall.constant.MqConst;
import com.atguigu.gmall.model.enums.OrderStatus;
import com.atguigu.gmall.model.order.OrderInfo;
import com.atguigu.gmall.order.service.OrderService;
import com.rabbitmq.client.Channel;
import lombok.SneakyThrows;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OrderReceiver {

    @Autowired
    private OrderService orderService;

    /**
     * 订单超时，判断订单状态
     * 已支付：不进行操作
     * 未支付：修改状态
     * @param orderId
     * @param message
     * @param channel
     */
    @SneakyThrows
    @RabbitListener(queues = MqConst.QUEUE_ORDER_CANCEL)
    public void cancelOrderStatus(Long orderId, Message message, Channel channel){
        //判断
        try {
            if(orderId!=null){
                //查询订单，根据id
                //先写查询订单的service，调用
                //使用通用service-继承接口，实现
                OrderInfo orderInfo = orderService.getById(orderId);
                //判断是否为空
                if(orderInfo!=null){
                    //获取状态进行判断
                    if("UNPAID".equals(orderInfo.getOrderStatus())&&"UNPAID".equals(orderInfo.getProcessStatus())){
                        //调用支付关闭订单
                        //处理超时订单
                        orderService.execExpiredOrder(orderId);
                    }

                }

            }
        } catch (Exception e) {
            //写入日志，数据库，短信
            e.printStackTrace();
        }
        //手动确认消息
        channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);


    }
}