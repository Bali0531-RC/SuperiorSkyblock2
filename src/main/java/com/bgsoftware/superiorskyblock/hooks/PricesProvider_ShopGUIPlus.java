package com.bgsoftware.superiorskyblock.hooks;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.key.Key;
import net.brcdev.shopgui.ShopGuiPlugin;
import net.brcdev.shopgui.shop.Shop;
import net.brcdev.shopgui.shop.ShopItem;

import java.math.BigDecimal;
import java.util.Map;

public final class PricesProvider_ShopGUIPlus implements PricesProvider{

    private static final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();
    private static final ShopGuiPlugin shopPlugin = ShopGuiPlugin.getInstance();

    @Override
    public BigDecimal getPrice(Key key) {
        double price = 0;

        Map<String, Shop> shops = shopPlugin.getShopManager().shops;
        for(Shop shop : shops.values()){
            for(ShopItem shopItem : shop.getShopItems()){
                if(Key.of(shopItem.getItem()).equals(key)){
                    double shopPrice;

                    switch (plugin.getSettings().syncWorth){
                        case BUY:
                            //noinspection deprecation
                            shopPrice = shopItem.getBuyPriceForAmount(1);
                            break;
                        case SELL:
                            //noinspection deprecation
                            shopPrice = shopItem.getSellPriceForAmount(1);
                            break;
                        default:
                            shopPrice = 0;
                            break;
                    }

                    if(shopPrice > price)
                        price = shopPrice;
                }
            }
        }

        return BigDecimal.valueOf(price);
    }

}
