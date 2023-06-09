package com.atguigu.gmall.payment.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.atguigu.gmall.model.enums.PaymentStatus;
import com.atguigu.gmall.model.enums.PaymentType;
import com.atguigu.gmall.model.order.OrderInfo;
import com.atguigu.gmall.model.payment.PaymentInfo;
import com.atguigu.gmall.order.client.OrderFeignClient;
import com.atguigu.gmall.payment.config.AlipayConfig;
import com.atguigu.gmall.payment.service.AlipayService;
import com.atguigu.gmall.payment.service.PaymentService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.filefilter.FalseFileFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalUnit;

@Service
@SuppressWarnings("all")
public class AlipayServiceImpl implements AlipayService {

    @Autowired
    private OrderFeignClient orderFeignClient;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private AlipayClient alipayClient;

    //支付宝下单
    @Override
    public String submitOrder(Long orderId) {

        //查询订单
        OrderInfo orderInfo = orderFeignClient.getOrderInfo(orderId);
        //判断
        if (orderInfo == null || !"UNPAID".equals(orderInfo.getOrderStatus())) {

            return "当前订单已经关闭或者已经支付";
        }
        //保存支付信息
        paymentService.savePaymentInfo(orderInfo, PaymentType.ALIPAY.name());

        //对接支付宝
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        //异步回调地址，仅支持http/https，公网可访问
        System.out.println("异步回调地址:"+AlipayConfig.notify_payment_url);
        request.setNotifyUrl(AlipayConfig.notify_payment_url);
        //同步回调地址，仅支持http/https
        request.setReturnUrl(AlipayConfig.return_payment_url);
        System.out.println("同步回调地址:");
        /******必传参数******/
        JSONObject bizContent = new JSONObject();
        //商户订单号，商家自定义，保持唯一性
        bizContent.put("out_trade_no", orderInfo.getOutTradeNo());
        //支付金额，最小值0.01元
        bizContent.put("total_amount", 0.01);
        //订单标题，不可使用特殊符号
        bizContent.put("subject", orderInfo.getTradeBody());
        //电脑网站支付场景固定传值FAST_INSTANT_TRADE_PAY
        bizContent.put("product_code", "FAST_INSTANT_TRADE_PAY");

        /******可选参数******/
        //设置超时时间--相对时间
//        bizContent.put("timeout_express", "10m");
        //设置超时时间--绝对时间
        LocalDateTime now = LocalDateTime.now();
        now=now.plusMinutes(10);
        String format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(now);
        bizContent.put("time_expire", format);

        //// 商品明细信息，按需传入
        //JSONArray goodsDetail = new JSONArray();
        //JSONObject goods1 = new JSONObject();
        //goods1.put("goods_id", "goodsNo1");
        //goods1.put("goods_name", "子商品1");
        //goods1.put("quantity", 1);
        //goods1.put("price", 0.01);
        //goodsDetail.add(goods1);
        //bizContent.put("goods_detail", goodsDetail);

        //// 扩展信息，按需传入
        //JSONObject extendParams = new JSONObject();
        //extendParams.put("sys_service_provider_id", "2088511833207846");
        //bizContent.put("extend_params", extendParams);

        request.setBizContent(bizContent.toJSONString());
        String form="";
        try {
            form = alipayClient.pageExecute(request).getBody();//调用SDK生成表单
        } catch (AlipayApiException e) {
            e.printStackTrace();
        }

        return form;
    }

    //发起退款
    @Override
    public boolean refund(Long orderId) {
        //根据orderId查询订单对象
        OrderInfo orderInfo = orderFeignClient.getOrderInfo(orderId);
        //判断
        if(orderInfo==null||!"PAID".equals(orderInfo.getOrderStatus())){
            return false;
        }

        AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
        JSONObject bizContent = new JSONObject();
        bizContent.put("out_trade_no",orderInfo.getOutTradeNo());
        bizContent.put("refund_amount", 0.01);
        bizContent.put("refund_reason", "不想要了，帮忙退一下!!!");

        //// 返回参数选项，按需传入
        //JSONArray queryOptions = new JSONArray();
        //queryOptions.add("refund_detail_item_list");
        //bizContent.put("query_options", queryOptions);

        request.setBizContent(bizContent.toString());
        AlipayTradeRefundResponse response = null;
        try {
            response = alipayClient.execute(request);
        } catch (AlipayApiException e) {
            e.printStackTrace();
        }
        if(response.isSuccess()){
            System.out.println("调用成功");
            //订单  已支付--关闭
            PaymentInfo paymentInfo=new PaymentInfo();
            paymentInfo.setPaymentStatus(PaymentStatus.CLOSED.name());
            //支付记录
            paymentService.updatePaymentInfoStatus(orderInfo.getOutTradeNo(),PaymentType.ALIPAY.name(),paymentInfo);

            //订单状态--发送mq消息

            return true;
        } else {
            System.out.println("调用失败");
            return false;
        }
    }

}
