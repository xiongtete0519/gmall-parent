package com.atguigu.gmall.list.service.impl;

import com.atguigu.gmall.list.repository.GoodsRepository;
import com.atguigu.gmall.list.service.SearchService;
import com.atguigu.gmall.model.list.Goods;
import com.atguigu.gmall.model.list.SearchAttr;
import com.atguigu.gmall.model.product.BaseAttrInfo;
import com.atguigu.gmall.model.product.BaseCategoryView;
import com.atguigu.gmall.model.product.BaseTrademark;
import com.atguigu.gmall.model.product.SkuInfo;
import com.atguigu.gmall.product.client.ProductFeignClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
@SuppressWarnings("all")
public class SearchServiceImpl implements SearchService {

    @Autowired
    private GoodsRepository goodsRepository;

    @Autowired
    private ProductFeignClient productFeignClient;

    //商品上架
    @Override
    public void upperGoods(Long skuId) {

        //创建封装数据的对象
        Goods goods = new Goods();
        //设置skuInfo
        SkuInfo skuInfo = productFeignClient.getSkuInfo(skuId);
        if(skuInfo!=null){
            goods.setId(skuId);
            goods.setDefaultImg(skuInfo.getSkuDefaultImg());
            goods.setTitle(skuInfo.getSkuName());
            goods.setPrice(productFeignClient.getSkuPrice(skuId).doubleValue());
            goods.setCreateTime(new Date());
            goods.setTmId(skuInfo.getTmId());

            //设置品牌信息
            BaseTrademark trademark = productFeignClient.getTrademark(skuInfo.getTmId());
            if(trademark!=null){
                goods.setTmName(trademark.getTmName());
                goods.setTmLogoUrl(trademark.getLogoUrl());
            }

            //设置分类信息
            BaseCategoryView categoryView = productFeignClient.getCategoryView(skuInfo.getCategory3Id());
            if(categoryView!=null){
                goods.setCategory1Id(categoryView.getCategory1Id());
                goods.setCategory2Id(categoryView.getCategory2Id());
                goods.setCategory3Id(categoryView.getCategory3Id());
                goods.setCategory1Name(categoryView.getCategory1Name());
                goods.setCategory2Name(categoryView.getCategory2Name());
                goods.setCategory3Name(categoryView.getCategory3Name());
            }
        }

        //设置平台属性信息
        List<BaseAttrInfo> attrList = productFeignClient.getAttrList(skuId);
        //判断
        if(!CollectionUtils.isEmpty(attrList)){
            //BaseAttrInfo->SearchAttr
            //List<BaseAttrInfo>->List<SearchAttr>
            List<SearchAttr> searchAttrList = attrList.stream().map(baseAttrInfo -> {
                SearchAttr searchAttr = new SearchAttr();
                //属性id
                searchAttr.setAttrId(baseAttrInfo.getId());
                //属性名称
                searchAttr.setAttrName(baseAttrInfo.getAttrName());
                //属性值名称
                searchAttr.setAttrValue(baseAttrInfo.getAttrValueList().get(0).getValueName());
                return searchAttr;
            }).collect(Collectors.toList());

            //添加到goods
            goods.setAttrs(searchAttrList);

        }

        //将数据添加到es
        goodsRepository.save(goods);
    }
}
