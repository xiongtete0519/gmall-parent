package com.atguigu.gmall.payment.service;

public interface AlipayService {

    //支付宝下单
    String submitOrder(Long orderId);

    //发起退款
    boolean refund(Long orderId);
}
