package com.atguigu.gmall.order.controller;

import com.atguigu.gmall.cart.client.CartFeignClient;
import com.atguigu.gmall.common.constant.RedisConst;
import com.atguigu.gmall.common.result.Result;
import com.atguigu.gmall.common.util.AuthContextHolder;
import com.atguigu.gmall.model.cart.CartInfo;
import com.atguigu.gmall.model.order.OrderDetail;
import com.atguigu.gmall.model.order.OrderInfo;
import com.atguigu.gmall.model.user.UserAddress;
import com.atguigu.gmall.order.service.OrderService;
import com.atguigu.gmall.product.client.ProductFeignClient;
import com.atguigu.gmall.user.client.UserFeignClient;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.ApiOperation;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/order")
@SuppressWarnings("all")
public class OrderApiController {

    @Autowired
    private UserFeignClient userFeignClient;

    @Autowired
    private CartFeignClient cartFeignClient;

    @Autowired
    private ProductFeignClient productFeignClient;

    @Autowired
    private OrderService orderService;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private ThreadPoolExecutor executor;

    @ApiOperation("根据订单id查询订单信息")
    @GetMapping("/inner/getOrderInfo/{orderId}")
    public OrderInfo getOrderInfo(@PathVariable Long orderId){
        return orderService.getOrderInfoById(orderId);
    }

    @ApiOperation("我的订单")
    @GetMapping("/auth/{page}/{limit}")
    public Result getOrderPageByUserId(@PathVariable Long page,@PathVariable Long limit,HttpServletRequest request){
        //获取用户id
        String userId = AuthContextHolder.getUserId(request);
        //处理分页参数
        Page<OrderInfo> orderInfoPage=new Page<>(page,limit);

        IPage<OrderInfo> orderInfoIPage=orderService.getOrderPageByUserId(orderInfoPage,userId);


        return Result.ok(orderInfoIPage);
    }

    @ApiOperation("提交订单")
    @PostMapping("/auth/submitOrder")
    public Result submitOrder(@RequestBody OrderInfo orderInfo,HttpServletRequest request){

        //获取用户id
        String userId = AuthContextHolder.getUserId(request);
        //校验流水号
        String tradeNo = request.getParameter("tradeNo");
        boolean result = orderService.checkTradeCode(userId, tradeNo);
        if(!result){
            return Result.fail().message("不能重复提交订单");
        }
        //验证库存
        List<OrderDetail> orderDetailList = orderInfo.getOrderDetailList();

        //定义集合收集异步对象
        List<CompletableFuture> completableFutureList=new ArrayList<>();

        //定义集合收集错误信息
        List<String> errorList=new ArrayList<>();
        if(!CollectionUtils.isEmpty(orderDetailList)){
            for (OrderDetail orderDetail : orderDetailList) {
                //异步编排--多线程操作
                CompletableFuture<Void> stockCompletableFuture = CompletableFuture.runAsync(() -> {
                    //验证库存
                    boolean flag = orderService.checkStock(String.valueOf(orderDetail.getSkuId()),
                            String.valueOf(orderDetail.getSkuNum()));
                    //处理
                    if (!flag) {
//                        return Result.fail().message(orderDetail.getSkuId()+orderDetail.getSkuName()+"库存不足！");
                         errorList.add(orderDetail.getSkuId()+orderDetail.getSkuName()+"库存不足！");
                    }
                }, executor);
                completableFutureList.add(stockCompletableFuture);


                //校验价格 缓存和mysql
                CompletableFuture<Void> skuPriceCompletableFuture = CompletableFuture.runAsync(() -> {
                    //获取实时价格
                    BigDecimal skuPrice = productFeignClient.getSkuPrice(orderDetail.getSkuId());
                    //比较
                    if (orderDetail.getOrderPrice().compareTo(skuPrice) != 0) {
                        //设置新的内容
                        List<CartInfo> cartCheckedList = cartFeignClient.getCartCheckedList(userId);
                        //更新到缓存
                        cartCheckedList.forEach(CartInfo -> {
                            BoundHashOperations<String, String, CartInfo> boundHashOps = redisTemplate.boundHashOps(RedisConst.USER_KEY_PREFIX + userId + RedisConst.USER_CART_KEY_SUFFIX);
                            boundHashOps.put(CartInfo.getSkuId().toString(), CartInfo);
                        });

//                        return Result.fail().message(orderDetail.getSkuId()+"价格有变动");
                        errorList.add(orderDetail.getSkuId()+"价格有变动");
                    }
                }, executor);
                completableFutureList.add(skuPriceCompletableFuture);
            }
        }
        //声明为一组
        CompletableFuture.allOf(completableFutureList.toArray(new CompletableFuture[completableFutureList.size()]))
                .join();
        //错误集合信息决定是否下订单
        if(errorList.size()>0){
            return Result.fail().message(StringUtils.join(errorList,","));
        }

        orderInfo.setUserId(Long.parseLong(userId));

        Long orderId=orderService.submitOrder(orderInfo);

        //删除流水号
        orderService.deleteTradeCode(userId);
        return Result.ok(orderId);
    }

    @ApiOperation("去结算")
    @GetMapping("/auth/trade")
    public Result trade(HttpServletRequest request){
        //创建Map封装数据
        Map<String,Object> resultMap=new HashMap<>();

        //获取用户id
        String userId = AuthContextHolder.getUserId(request);
        //查询地址列表
        List<UserAddress> userAddressListByUserId = userFeignClient.findUserAddressListByUserId(Long.parseLong(userId));
        //封装购物列表
        List<CartInfo> cartCheckedList = cartFeignClient.getCartCheckedList(userId);
        List<OrderDetail> orderDetailList=null;
        //判断
        if(!CollectionUtils.isEmpty(cartCheckedList)){
                orderDetailList = cartCheckedList.stream().map(cartInfo -> {
                //创建订单明细对象
                OrderDetail orderDetail = new OrderDetail();
                orderDetail.setSkuId(cartInfo.getSkuId());
                orderDetail.setSkuName(cartInfo.getSkuName());
                orderDetail.setImgUrl(cartInfo.getImgUrl());
                orderDetail.setOrderPrice(productFeignClient.getSkuPrice(cartInfo.getSkuId()));
                orderDetail.setSkuNum(cartInfo.getSkuNum());
                return orderDetail;
            }).collect(Collectors.toList());
        }
        //数量
//        int skuNum = orderDetailList.size();
        int skuNum =0;
        for (OrderDetail orderDetail : orderDetailList) {
            skuNum+=orderDetail.getSkuNum();
        }
        //总金额
        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setOrderDetailList(orderDetailList);
        //计算总金额--在计算之前必须设置订单明细集合
        orderInfo.sumTotalAmount();
        resultMap.put("userAddressList",userAddressListByUserId);
        resultMap.put("detailArrayList",orderDetailList);
        resultMap.put("totalNum",skuNum);
        resultMap.put("totalAmount",orderInfo.getTotalAmount());

        //携带流水号
        String tradeNo = orderService.getTradeNo(userId);
        //存储流水号
        resultMap.put("tradeNo",tradeNo);


        return Result.ok(resultMap);
    }


}
