/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;

import com.soapboxrace.core.bo.util.AchievementCommerceContext;
import com.soapboxrace.core.bo.util.OwnedCarConverter;
import com.soapboxrace.core.dao.*;
import com.soapboxrace.core.engine.EngineException;
import com.soapboxrace.core.engine.EngineExceptionCode;
import com.soapboxrace.core.jpa.*;
import com.soapboxrace.jaxb.http.ArrayOfOwnedCarTrans;
import com.soapboxrace.jaxb.http.CommerceResultStatus;
import com.soapboxrace.jaxb.http.CommerceResultTrans;
import com.soapboxrace.jaxb.http.OwnedCarTrans;
import com.soapboxrace.jaxb.util.JAXBUtility;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

@Stateless
public class BasketBO {

    @EJB
    private PersonaBO personaBo;

    @EJB
    private ParameterBO parameterBO;

    @EJB
    private BasketDefinitionDAO basketDefinitionsDAO;

    @EJB
    private CarSlotDAO carSlotDAO;

    @EJB
    private OwnedCarDAO ownedCarDAO;

    @EJB
    private CardPackDAO cardPackDAO;

    @EJB
    private ProductDAO productDao;

    @EJB
    private PersonaDAO personaDao;

    @EJB
    private TokenSessionBO tokenSessionBO;

    @EJB
    private InventoryDAO inventoryDao;

    @EJB
    private InventoryItemDAO inventoryItemDao;

    @EJB
    private InventoryBO inventoryBO;

    @EJB
    private TreasureHuntDAO treasureHuntDAO;

    @EJB
    private CarStatsDAO carStatsDAO;

    @EJB
    private AchievementBO achievementBO;

    @EJB
    private DriverPersonaBO driverPersonaBO;

    @EJB
    private PerformanceBO performanceBO;

    @EJB
    private ItemRewardBO itemRewardBO;

    @EJB
    private CarDamageBO carDamageBO;

    @EJB
    private CarSlotBO carSlotBO;

    @EJB
    private AmplifierDAO amplifierDAO;

    public ProductEntity findProduct(String productId) {
        return productDao.findByProductId(productId);
    }

    public CommerceResultStatus repairCar(String productId, PersonaEntity personaEntity) {
        CarSlotEntity defaultCarEntity = personaBo.getDefaultCarEntity(personaEntity.getPersonaId());
        int price =
                (int) (productDao.findByProductId(productId).getPrice() * (100 - defaultCarEntity.getOwnedCar().getDurability()));
        ProductEntity repairProduct = productDao.findByProductId(productId);

        if (repairProduct == null) {
            return CommerceResultStatus.FAIL_INVALID_BASKET;
        }
        if (this.canPurchaseProduct(personaEntity, repairProduct, price)) {
            personaDao.update(personaEntity);
            OwnedCarEntity ownedCarEntity = defaultCarEntity.getOwnedCar();
            carDamageBO.updateDurability(ownedCarEntity, 100);
            carSlotDAO.update(defaultCarEntity);
            this.performPersonaTransaction(personaEntity, repairProduct, price);
            return CommerceResultStatus.SUCCESS;
        }

        return CommerceResultStatus.FAIL_INSUFFICIENT_FUNDS;
    }

    public CommerceResultStatus buyPowerups(String productId, PersonaEntity personaEntity) {
        if (!parameterBO.getBoolParam("ENABLE_ECONOMY")) {
            return CommerceResultStatus.FAIL_INSUFFICIENT_FUNDS;
        }
        ProductEntity powerupProduct = productDao.findByProductId(productId);

        if (powerupProduct == null) {
            return CommerceResultStatus.FAIL_INVALID_BASKET;
        }

        if (canPurchaseProduct(personaEntity, powerupProduct)) {
            InventoryEntity inventoryEntity = inventoryDao.findByPersonaId(personaEntity.getPersonaId());
            inventoryBO.addStackedInventoryItem(inventoryEntity, productId, powerupProduct.getUseCount());
            inventoryDao.update(inventoryEntity);
            performPersonaTransaction(personaEntity, powerupProduct);
            return CommerceResultStatus.SUCCESS;
        }

        return CommerceResultStatus.FAIL_INSUFFICIENT_FUNDS;
    }

    public CommerceResultStatus buyCar(ProductEntity productEntity, PersonaEntity personaEntity, TokenSessionEntity tokenSessionEntity,
                                       CommerceResultTrans commerceResultTrans) {
        if (getPersonaCarCount(personaEntity.getPersonaId()) >= parameterBO.getCarLimit(tokenSessionEntity.getUserEntity())) {
            return CommerceResultStatus.FAIL_INSUFFICIENT_CAR_SLOTS;
        }

        if (canPurchaseProduct(personaEntity, productEntity)) {
            try {
                CarSlotEntity carSlotEntity = addCar(productEntity, personaEntity);
                personaBo.changeDefaultCar(personaEntity, carSlotEntity.getOwnedCar().getId());
                personaDao.update(personaEntity);

                ArrayOfOwnedCarTrans arrayOfOwnedCarTrans = new ArrayOfOwnedCarTrans();
                OwnedCarTrans ownedCarTrans = OwnedCarConverter.entity2Trans(carSlotEntity.getOwnedCar());
                commerceResultTrans.setPurchasedCars(arrayOfOwnedCarTrans);
                arrayOfOwnedCarTrans.getOwnedCarTrans().add(ownedCarTrans);

                performPersonaTransaction(personaEntity, productEntity);
            } catch (EngineException e) {
                return CommerceResultStatus.FAIL_MAX_STACK_OR_RENTAL_LIMIT;
            }

            return CommerceResultStatus.SUCCESS;
        }

        return CommerceResultStatus.FAIL_INSUFFICIENT_FUNDS;
    }

    public CommerceResultStatus buyBundle(String productId, PersonaEntity personaEntity, CommerceResultTrans commerceResultTrans) {
        ProductEntity bundleProduct = productDao.findByProductId(productId);

        if (bundleProduct == null) {
            return CommerceResultStatus.FAIL_INVALID_BASKET;
        }

        if (canPurchaseProduct(personaEntity, bundleProduct)) {
            try {
                CardPackEntity cardPackEntity = cardPackDAO.findByEntitlementTag(bundleProduct.getEntitlementTag());

                if (cardPackEntity == null) {
                    throw new EngineException("Could not find card pack with name: " + bundleProduct.getEntitlementTag() + " (product ID: " + productId + ")", EngineExceptionCode.LuckyDrawCouldNotDrawProduct, true);
                }

                for (CardPackItemEntity cardPackItemEntity : cardPackEntity.getItems()) {
                    itemRewardBO.convertRewards(
                            itemRewardBO.getRewards(personaEntity, cardPackItemEntity.getScript()),
                            commerceResultTrans
                    );
                }

                performPersonaTransaction(personaEntity, bundleProduct);

                return CommerceResultStatus.SUCCESS;
            } catch (EngineException e) {
                throw new EngineException("Error occurred in bundle purchase (product ID: " + productId + ")", e, e.getCode(), true);
            }
        }

        return CommerceResultStatus.FAIL_INSUFFICIENT_FUNDS;
    }

    public CommerceResultStatus reviveTreasureHunt(String productId, PersonaEntity personaEntity) {
        ProductEntity productEntity = productDao.findByProductId(productId);

        if (canPurchaseProduct(personaEntity, productEntity)) {
            TreasureHuntEntity treasureHuntEntity = treasureHuntDAO.find(personaEntity.getPersonaId());
            treasureHuntEntity.setIsStreakBroken(false);
            performPersonaTransaction(personaEntity, productEntity);

            treasureHuntDAO.update(treasureHuntEntity);

            return CommerceResultStatus.SUCCESS;
        }

        return CommerceResultStatus.FAIL_INSUFFICIENT_FUNDS;
    }

    public CommerceResultStatus buyAmplifier(PersonaEntity personaEntity, String productId) {
        ProductEntity productEntity = productDao.findByProductId(productId);

        if (!canAddAmplifier(personaEntity.getPersonaId(), productEntity.getEntitlementTag())) {
            return CommerceResultStatus.FAIL_MAX_ALLOWED_PURCHASES_FOR_THIS_PRODUCT;
        }

        if (canPurchaseProduct(personaEntity, productEntity)) {
            addAmplifier(personaEntity, productEntity);
            performPersonaTransaction(personaEntity, productEntity);
            return CommerceResultStatus.SUCCESS;
        }

        return CommerceResultStatus.FAIL_INSUFFICIENT_FUNDS;
    }

    public CarSlotEntity addCar(ProductEntity productEntity, PersonaEntity personaEntity) {
        Objects.requireNonNull(productEntity, "productEntity is null");

        /*
        rentals cannot be purchased unless you have one other car
        rentals will not be considered when evaluating whether or not you can sell a car
        so if you own one regular car and one rental, you cannot sell the regular car no matter what
        until you get another regular car
         */

        boolean isRental = productEntity.getDurationMinute() > 0;
        if (isRental) {
            Long numNonRentals = carSlotDAO.findNumNonRentalsByPersonaId(personaEntity.getPersonaId());

            if (numNonRentals.equals(0L)) {
                throw new EngineException("Persona " + personaEntity.getName() + " has no non-rental cars", EngineExceptionCode.MissingRequiredEntitlements, true);
            }
        }

        OwnedCarTrans ownedCarTrans = getCar(productEntity);
        ownedCarTrans.setId(0L);
        ownedCarTrans.getCustomCar().setId(0);

        CarSlotEntity carSlotEntity = new CarSlotEntity();
        carSlotEntity.setPersona(personaEntity);

        OwnedCarEntity ownedCarEntity = new OwnedCarEntity();
        ownedCarEntity.setCarSlot(carSlotEntity);

        CustomCarEntity customCarEntity = new CustomCarEntity();
        customCarEntity.setOwnedCar(ownedCarEntity);
        ownedCarEntity.setCustomCar(customCarEntity);
        carSlotEntity.setOwnedCar(ownedCarEntity);
        OwnedCarConverter.trans2Entity(ownedCarTrans, ownedCarEntity);
        OwnedCarConverter.details2NewEntity(ownedCarTrans, ownedCarEntity);

        if (isRental) {
            ownedCarEntity.setExpirationDate(LocalDateTime.now().plusMinutes(productEntity.getDurationMinute()));
            ownedCarEntity.setOwnershipType("RentalCar");
        }

        carSlotDAO.insert(carSlotEntity);

        performanceBO.calcNewCarClass(carSlotEntity.getOwnedCar().getCustomCar());

        if (isRental && canAddAmplifier(personaEntity.getPersonaId(), "INSURANCE_AMPLIFIER")) {
            addAmplifier(personaEntity, productDao.findByEntitlementTag("INSURANCE_AMPLIFIER"));
        }

        CarStatsEntity carStatsEntity =
                carStatsDAO.find(carSlotEntity.getOwnedCar().getCustomCar().getPhysicsProfileHash());

        AchievementTransaction transaction = achievementBO.createTransaction(personaEntity.getPersonaId());

        if (carStatsEntity != null) {
            AchievementCommerceContext commerceContext = new AchievementCommerceContext(carStatsEntity,
                    AchievementCommerceContext.CommerceType.CAR_PURCHASE);
            transaction.add("COMMERCE", Map.of("persona", personaEntity, "carSlot", carSlotEntity, "commerceCtx", commerceContext));
            achievementBO.commitTransaction(personaEntity, transaction);
        }

        return carSlotEntity;
    }

    public boolean sellCar(TokenSessionEntity tokenSessionEntity, Long personaId, Long serialNumber) {
        this.tokenSessionBO.verifyPersonaOwnership(tokenSessionEntity, personaId);

        OwnedCarEntity ownedCarEntity = ownedCarDAO.find(serialNumber);
        if (ownedCarEntity == null) {
            return false;
        }

        if ("RentalCar".equalsIgnoreCase(ownedCarEntity.getOwnershipType())) {
            return false;
        }

        PersonaEntity personaEntity = personaDao.find(personaId);

        if (!removeCar(personaEntity, serialNumber)) {
            return false;
        }

        double cashTotal = personaEntity.getCash() + ownedCarEntity.getCustomCar().getResalePrice();
        driverPersonaBO.updateCash(personaEntity, cashTotal);

        return true;
    }

    public boolean removeCar(PersonaEntity personaEntity, Long serialNumber) {
        OwnedCarEntity ownedCarEntity = ownedCarDAO.find(serialNumber);
        if (ownedCarEntity == null) {
            return false;
        }
        CarSlotEntity carSlotEntity = ownedCarEntity.getCarSlot();
        if (carSlotEntity == null) {
            return false;
        }
        if (!carSlotEntity.getPersona().getPersonaId().equals(personaEntity.getPersonaId())) {
            throw new EngineException(EngineExceptionCode.CarNotOwnedByDriver, false);
        }
        Long nonRentalCarCount = carSlotDAO.findNumNonRentalsByPersonaId(personaEntity.getPersonaId());

        // If the car is not a rental, check the number of non-rentals
        if (!"RentalCar".equalsIgnoreCase(carSlotEntity.getOwnedCar().getOwnershipType())) {
            if (nonRentalCarCount <= 1) {
                return false;
            }
        } else if (nonRentalCarCount == 0) {
            return false;
        }

        carSlotDAO.delete(carSlotEntity);

        int curCarIndex = personaEntity.getCurCarIndex();

        if (curCarIndex > 0) {
            // Best case: we just decrement the current car index
            personaEntity.setCurCarIndex(curCarIndex - 1);
        } else {
            // Worst case: count cars again and subtract 1 to get new index
            personaEntity.setCurCarIndex(carSlotDAO.findNumByPersonaId(personaEntity.getPersonaId()) - 1);
        }

        personaDao.update(personaEntity);

        return true;
    }

    private boolean canAddAmplifier(Long personaId, String entitlementTag) {
        return inventoryItemDao.findAllByPersonaIdAndEntitlementTag(personaId, entitlementTag).isEmpty();
    }

    private void addAmplifier(PersonaEntity personaEntity, ProductEntity productEntity) {
        InventoryEntity inventoryEntity = inventoryBO.getInventory(personaEntity);
        inventoryBO.addInventoryItem(inventoryEntity, productEntity.getProductId());

        AmplifierEntity amplifierEntity = amplifierDAO.findAmplifierByHash(productEntity.getHash());

        if (amplifierEntity.getAmpType().equals("INSURANCE")) {
            personaBo.repairAllCars(personaEntity);
        }
    }

    private OwnedCarTrans getCar(ProductEntity productEntity) {
        Objects.requireNonNull(productEntity, "productEntity is null");
        String productId = productEntity.getProductId();
        BasketDefinitionEntity basketDefinitionEntity = basketDefinitionsDAO.find(productId);
        if (basketDefinitionEntity == null) {
            throw new IllegalArgumentException(String.format("No basket definition for %s", productId));
        }
        String ownedCarTrans = basketDefinitionEntity.getOwnedCarTrans();
        OwnedCarTrans ownedCarTrans1 = JAXBUtility.unMarshal(ownedCarTrans, OwnedCarTrans.class);

        if (productEntity.getDurationMinute() != 0) {
            ownedCarTrans1.setOwnershipType("RentalCar");
        }

        // do this automatically in case we get any weird data in our table
        ownedCarTrans1.setHeat(1.0f);
        ownedCarTrans1.setDurability(100);
        ownedCarTrans1.getCustomCar().setResalePrice(productEntity.getResalePrice());

        return ownedCarTrans1;
    }

    private int getPersonaCarCount(Long personaId) {
        return carSlotBO.countPersonasCar(personaId);
    }

    private boolean canPurchaseProduct(PersonaEntity personaEntity, ProductEntity productEntity) {
        return canPurchaseProduct(personaEntity, productEntity, -1);
    }

    private boolean canPurchaseProduct(PersonaEntity personaEntity, ProductEntity productEntity, float valueOverride) {
        if (productEntity.isEnabled()) {
            // non-premium products are available to all; user must be premium to purchase a premium product
            if (productEntity.isPremium() && !personaEntity.getUser().isPremium()) {
                return false;
            }

            float price = valueOverride == -1 ? productEntity.getPrice() : valueOverride;

            switch (productEntity.getCurrency()) {
                case "CASH":
                    return personaEntity.getCash() >= price;
                case "_NS":
                    return personaEntity.getBoost() >= price;
                default:
                    throw new EngineException("Invalid currency in product entry: " + productEntity.getCurrency(), EngineExceptionCode.UnspecifiedError, true);
            }
        }

        return false;
    }

    private void performPersonaTransaction(PersonaEntity personaEntity, ProductEntity productEntity) {
        performPersonaTransaction(personaEntity, productEntity, -1);
    }

    private void performPersonaTransaction(PersonaEntity personaEntity, ProductEntity productEntity, float valueOverride) {
        float price = valueOverride == -1 ? productEntity.getPrice() : valueOverride;

        switch (productEntity.getCurrency()) {
            case "CASH":
                personaEntity.setCash(personaEntity.getCash() - price);
                break;
            case "_NS":
                personaEntity.setBoost(personaEntity.getBoost() - price);
                break;
            default:
                throw new EngineException("Invalid currency in product entry: " + productEntity.getCurrency(), EngineExceptionCode.UnspecifiedError, true);
        }

        personaDao.update(personaEntity);
    }
}