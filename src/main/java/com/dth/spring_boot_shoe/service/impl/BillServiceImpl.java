package com.dth.spring_boot_shoe.service.impl;

import com.dth.spring_boot_shoe.dto.ProductDetail;
import com.dth.spring_boot_shoe.entity.*;
import com.dth.spring_boot_shoe.constant.StatusBill;
import com.dth.spring_boot_shoe.dto.BillDTO;
import com.dth.spring_boot_shoe.dto.BillReceiptDTO;
import com.dth.spring_boot_shoe.dto.ProductDetailDTO;
import com.dth.spring_boot_shoe.exception.ApiRequestException;
import com.dth.spring_boot_shoe.exception.RequestException;
import com.dth.spring_boot_shoe.repository.*;
import com.dth.spring_boot_shoe.request.CartRequest;
import com.dth.spring_boot_shoe.request.ProductExportRequest;
import com.dth.spring_boot_shoe.request.UserRequest;
import com.dth.spring_boot_shoe.response.BillDetailResponse;
import com.dth.spring_boot_shoe.response.BillResponse;
import com.dth.spring_boot_shoe.response.CheckQuantity;
import com.dth.spring_boot_shoe.response.SizeQuantity;
import com.dth.spring_boot_shoe.service.*;
import com.dth.spring_boot_shoe.utils.ExportReport;
import com.dth.spring_boot_shoe.utils.SendMailService;
import com.dth.spring_boot_shoe.utils.StringUtils;
import lombok.RequiredArgsConstructor;
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import javax.mail.MessagingException;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class BillServiceImpl implements BillService {

    private final BillRepository billRepository;
    private final ProductBillRepository productBillRepository;
    private final ProductDetailRepository productDetailRepository;
    private final ImageService imageService;
    private final UserRepository userRepository;
    private final ProductReceiptRepository productReceiptRepository;
    private final CommentRepository commentRepository;
    private final SendMailService sendMailService;
    private final ProductService productService;
    private final DiscountRepository discountRepository;
    private final ExportReport exportReport;
    private final FirebaseService firebaseService;

    @Override
    public BillEntity findByUserId() {
        UserEntity user = userRepository.findByEmailAndStatusAndEnabled(SecurityContextHolder.getContext().getAuthentication().getName(),1,true).orElseThrow(()->new RequestException("Bạn chưa đăng nhập","login"));
        return billRepository.findByUserIdAndBillType(user.getId(),0);
    }

    @Override
    public ProductDetailDTO addCart(UserEntity user, CartRequest request) {
        BillEntity bill=billRepository.findByUserIdAndBillType(user.getId(),0);
        ProductDetailEntity productDetail=productDetailRepository.findById(request.getProductDetailId()).get();
        if(bill==null){
            bill=new BillEntity();
            bill.setUser(user);
            bill.setBillType(0);
            bill=billRepository.save(bill);
        }
        ProductBillEntity productBill=productBillRepository.findByBillIdAndProductDetailId(bill.getId(),request.getProductDetailId());
        if(productBill==null){
            productBill=new ProductBillEntity();
            productBill.setProductDetail(productDetail);
            productBill.setBill(bill);
            productBill.setPrice(request.getPrice());
            productBill.setQuantity(1);
        }else {
            productBill.setQuantity(productBill.getQuantity()+1);
        }
        productBillRepository.save(productBill);
        return  productService.findByIdAndColor(productBill.getProductDetail().getId(),productBill.getProductDetail().getColor().getSlug());
    }


    @Override
    public List<BillReceiptDTO> loadCart(CartRequest[] request) {
        List<BillReceiptDTO> detailEntities=new ArrayList<>();
        Optional<UserEntity> user=userRepository.findByEmailAndStatusAndEnabled(SecurityContextHolder.getContext().getAuthentication().getName(),1,true);
        if(user.isPresent()){
            BillEntity bill=updateCartFromLocal(user.get(),request);
            List<ProductBillEntity> entities=productBillRepository.findByBillId(bill.getId());
            entities.forEach(entity-> {
                Optional<DiscountEntity> discount = discountRepository.findById(entity.getProductDetail().getDiscountId());
                Integer dis = discount.filter(discountEntity -> discountEntity.getEvent().getEndAt().isAfter(LocalDateTime.now())).map(DiscountEntity::getDiscount).orElse(0);
                ProductDetail detail = ProductDetail.converter(entity.getProductDetail(),
                        imageService.findByColorIdAndProductIdAndParent(entity.getProductDetail().getColor().getId(), entity.getProductDetail().getProduct().getId()),
                        dis);
                detailEntities.add(new BillReceiptDTO(entity.getQuantity(),detail));
            });
            bill.setPaying(0);
            billRepository.save(bill);
            return detailEntities;
        }
        Arrays.stream(request).forEach(cart->{
            ProductDetailEntity entity=productDetailRepository.findById(cart.getProductDetailId()).get();
            Optional<DiscountEntity> discount = discountRepository.findById(entity.getDiscountId());
            Integer dis = discount.filter(discountEntity -> discountEntity.getEvent().getEndAt().isAfter(LocalDateTime.now())).map(DiscountEntity::getDiscount).orElse(0);
            ProductDetail detail = ProductDetail.converter(entity,
                    imageService.findByColorIdAndProductIdAndParent(entity.getColor().getId(), entity.getProduct().getId()),
                    dis);
            detailEntities.add(new BillReceiptDTO(cart.getQuantity(),detail));
        });
        return detailEntities;
    }

    @Override
    public List<BillReceiptDTO> loadCartFixed(CartRequest[] request) {
        List<BillReceiptDTO> detailEntities=new ArrayList<>();
        //get product by cart from local
        Arrays.stream(request).forEach(cart->{
            ProductDetailEntity entity=productDetailRepository.findById(cart.getProductDetailId()).get();
            Optional<DiscountEntity> discount = discountRepository.findById(entity.getDiscountId());
            Integer dis = discount.filter(discountEntity -> discountEntity.getEvent().getEndAt().isAfter(LocalDateTime.now())).map(DiscountEntity::getDiscount).orElse(0);
            ProductDetail detail = ProductDetail.converter(entity,
                    imageService.findByColorIdAndProductIdAndParent(entity.getColor().getId(), entity.getProduct().getId()),
                    dis);
            detailEntities.add(new BillReceiptDTO(cart.getQuantity(),detail));

        });
        //get product if user has bill
        Optional<UserEntity> user=userRepository.findByEmailAndStatusAndEnabled(SecurityContextHolder.getContext().getAuthentication().getName(),1,true);
        if(user.isPresent()){
            BillEntity bill=billRepository.findByUserIdAndBillType(user.get().getId(),0);
            if(bill != null){
                List<ProductBillEntity> entities=productBillRepository.findByBillId(bill.getId());
                entities.forEach(entity->{
                    if(!checkExist(detailEntities,entity.getProductDetail().getId())) {
                        Optional<DiscountEntity> discount = discountRepository.findById(entity.getProductDetail().getDiscountId());
                        Integer dis = discount.filter(discountEntity -> discountEntity.getEvent().getEndAt().isAfter(LocalDateTime.now())).map(DiscountEntity::getDiscount).orElse(0);
                        ProductDetail detail = ProductDetail.converter(entity.getProductDetail(),
                                imageService.findByColorIdAndProductIdAndParent(entity.getProductDetail().getColor().getId(), entity.getProductDetail().getProduct().getId()),
                                dis);
                        detailEntities.add(new BillReceiptDTO(entity.getQuantity(),detail));
                    }
                });
            }
        }

        return detailEntities;
    }

    private boolean checkExist(List<BillReceiptDTO> list,Long id){
        for (BillReceiptDTO billReceiptDTO : list) {
            if (billReceiptDTO.getDetail().getId() == id) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<BillReceiptDTO> loadCart() {
        List<BillReceiptDTO> detailEntities=new ArrayList<>();
        Optional<UserEntity> user=userRepository.findByEmailAndStatusAndEnabled(SecurityContextHolder.getContext().getAuthentication().getName(),1,true);
        if(user.isPresent()){
            BillEntity bill=billRepository.findByUserIdAndBillType(user.get().getId(),0);
            if(bill==null){return null;}
            List<ProductBillEntity> entities=productBillRepository.findByBillId(bill.getId());
            entities.forEach(entity->{
                Optional<DiscountEntity> discount = discountRepository.findById(entity.getProductDetail().getDiscountId());
                Integer dis = discount.filter(discountEntity -> discountEntity.getEvent().getEndAt().isAfter(LocalDateTime.now())).map(DiscountEntity::getDiscount).orElse(0);
                ProductDetail detail = ProductDetail.converter(entity.getProductDetail(),
                        imageService.findByColorIdAndProductIdAndParent(entity.getProductDetail().getColor().getId(), entity.getProductDetail().getProduct().getId()),
                        dis);
                detailEntities.add(new BillReceiptDTO(entity.getQuantity(),detail));
            });
            return detailEntities;
        }
        return null;
    }

    @Override
    public BillEntity updateCartFromLocal(UserEntity user,CartRequest[] request) {
        BillEntity bill=billRepository.findByUserIdAndBillType(user.getId(),0);
        if(bill==null){
            bill=new BillEntity();
            bill.setUser(user);
            bill.setBillType(0);
            bill=billRepository.save(bill);
        }
        BillEntity finalBill = bill;
        Arrays.stream(request).forEach(cart->{
            ProductDetailEntity productDetail=productDetailRepository.findById(cart.getProductDetailId()).get();
            Optional<DiscountEntity> discount = discountRepository.findById(productDetail.getDiscountId());
            Integer dis = discount.filter(discountEntity -> discountEntity.getEvent().getEndAt().isAfter(LocalDateTime.now())).map(DiscountEntity::getDiscount).orElse(0);
            BigDecimal price = productDetail.getProduct().getPrice().multiply(new BigDecimal(100-dis)).divide(new BigDecimal(100));
            ProductBillEntity productBill=productBillRepository.findByBillIdAndProductDetailId(finalBill.getId(),cart.getProductDetailId());
            if(productBill==null){
                productBill=new ProductBillEntity();
                productBill.setProductDetail(productDetail);
                productBill.setBill(finalBill);
                productBill.setPrice(price);
                productBill.setQuantity(cart.getQuantity());
            }else {
                productBill.setPrice(price);
                productBill.setQuantity(cart.getQuantity());
            }
            productBillRepository.save(productBill);
        });
        return finalBill;
    }

    @Override
    public void update(UserEntity user, CartRequest request) {
        BillEntity bill=billRepository.findByUserIdAndBillType(user.getId(),0);
        ProductBillEntity productBill=productBillRepository.findByBillIdAndProductDetailId(bill.getId(),request.getProductDetailId());
        productBill.setQuantity(request.getQuantity());
        productBillRepository.save(productBill);
    }

    @Override
    public void delete(UserEntity user, CartRequest request) {
        BillEntity bill=billRepository.findByUserIdAndBillType(user.getId(),0);
        ProductBillEntity productBill=productBillRepository.findByBillIdAndProductDetailId(bill.getId(),request.getProductDetailId());
        productBillRepository.delete(productBill);
        List<ProductBillEntity> entities=productBillRepository.findByBillId(bill.getId());
        if(entities.size()<1){
            billRepository.delete(bill);
        }
    }

    @Override
    public List<SizeQuantity> findQuantityProductDetail(List<BillReceiptDTO> dtos) {
        List<SizeQuantity> quantities=new ArrayList<>();
        dtos.forEach(dto->{
            List<Object[]> objects=productReceiptRepository.findByProductIdGroupBySizeId(dto.getDetail().getId());
            quantities.add(SizeQuantity.converter(objects.get(0),dto.getDetail().getDiscount()));
        });
        return quantities;
    }

    @Override
    public List<CheckQuantity> checkQuantityProductToOrder(CartRequest[] request) {
        List<CheckQuantity> quantities=new ArrayList<>();
        Arrays.stream(request).forEach(dto->{
            List<Object[]> objects=productReceiptRepository.findByProductIdGroupBySizeId(dto.getProductDetailId());
            SizeQuantity sizeQuantity=SizeQuantity.converter(objects.get(0),null);

            quantities.add(new CheckQuantity(sizeQuantity, sizeQuantity.getQuantity() < dto.getQuantity()));
        });
        return quantities;
    }

    //get cart to delivery, change paying to 1
    @Override
    public List<BillReceiptDTO> updateToBill() {
        UserEntity user=userRepository.findByEmailAndStatusAndEnabled(SecurityContextHolder.getContext().getAuthentication().getName(),1,true)
                .orElseThrow(()->new RequestException("Bạn chưa đăng nhập","login"));
        List<BillReceiptDTO> details = new ArrayList<>();
        BillEntity bill=billRepository.findByUserIdAndBillType(user.getId(),0);
        if(bill==null){throw new RequestException("Giỏ hàng hiện tại của bạn trống","cart");}
        List<ProductBillEntity> entities=productBillRepository.findByBillId(bill.getId());
        entities.forEach(entity-> {
            Optional<DiscountEntity> discount = discountRepository.findById(entity.getProductDetail().getDiscountId());
            Integer dis = discount.filter(discountEntity -> discountEntity.getEvent().getEndAt().isAfter(LocalDateTime.now())).map(DiscountEntity::getDiscount).orElse(0);
            ProductDetail detail = ProductDetail.converter(entity.getProductDetail(),
                    imageService.findByColorIdAndProductIdAndParent(entity.getProductDetail().getColor().getId(), entity.getProductDetail().getProduct().getId()),
                    dis);
            details.add(new BillReceiptDTO(entity.getQuantity(),detail));
        });
        bill.setPaying(1);
        billRepository.save(bill);
        return details;
    }

    //đặt hàng
    @Override
    public void updateToBillDelivery(UserRequest dto) {
        UserEntity user=userRepository.findByEmailAndStatusAndEnabled(dto.getEmail(),1,true)
                .orElseThrow(()->new RequestException("Bạn chưa đăng nhập","login"));
        Integer count = billRepository.getCountBillByUser(user.getId());
        if(count>2){
            throw new RequestException("Bạn chỉ có tối đa 2 đơn được đặt, hãy thanh toán để tiếp tục đặt. Cảm ơn!","cart");
        }
        BillEntity bill=billRepository.findByUserIdAndBillType(user.getId(),0);
        List<ProductBillEntity> entities=productBillRepository.findByBillId(bill.getId());
        double total=entities.stream().mapToDouble(entity-> entity.getPrice().doubleValue()* (double)entity.getQuantity()).sum();
        BigDecimal tt1=new BigDecimal("0.00");
        BigDecimal tt2=new BigDecimal(total);
        BigDecimal r=tt1.add(tt2);
        bill.setTotalprice(r);
        bill.setCreatedAt(LocalDateTime.now());
        bill.setAddress(dto.getProvince()+"-"+dto.getDistrict()+"-"+dto.getWard()+"-"+dto.getVillage());
        billRepository.save(bill);
        user.setFullName(dto.getLastName()+" "+dto.getFirstName());
        user.setPhone(dto.getPhone());
        userRepository.save(user);

    }

    @Override
    public void updateToBillForPayment(String payment,HttpServletRequest request) {
        UserEntity user=userRepository.findByEmailAndStatusAndEnabled(SecurityContextHolder.getContext().getAuthentication().getName(),1,true)
                .orElseThrow(()->new RequestException("Bạn chưa đăng nhập","login"));
        BillEntity bill=billRepository.findByUserIdAndBillType(user.getId(),0);
        bill.setBillType(1);
        bill.setPaying(0);
        bill.setPayment(payment);
        bill.setStatus(StatusBill.waiting.toString());
        billRepository.save(bill);
        List<BillReceiptDTO> billReceiptDTOS = new ArrayList<>();
        List<ProductBillEntity> entities = productBillRepository.findByBillId(bill.getId());
        entities.forEach(e->{
            ProductDetail detail = ProductDetail.converter(e.getProductDetail(),
                    imageService.findByColorIdAndProductIdAndParent(e.getProductDetail().getColor().getId(),e.getProductDetail().getProduct().getId()),0);
            detail.setPrice(e.getPrice());
            billReceiptDTOS.add(new BillReceiptDTO(e.getQuantity(),detail));
        });

        try {
            String subject = "Thông báo xác nhận đơn hàng!";
            String htmlMsg = "<p><strong>Cám ơn bạn đã mua hàng!</strong></p><p>Bên mình đã nhận được đơn hàng của bạn và đang trong quá trình xử lý, bên mình sẽ giao sản phẩm đến với bạn sớm nhất.</p><a href='[[URL]]' target='_self'>Xem đơn hàng</a><p>Cảm ơn !</p>";

            sendMailService.sendMailOrder(user,billReceiptDTOS,bill,subject,htmlMsg,request);
        } catch (MessagingException |UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }

    @Override
    public List<BillDTO> findAllBillByUser(Long userId) {
        List<BillDTO> dtos=new ArrayList<>();
        List<BillEntity> entities=billRepository.findByUserIdAndBillTypeAndStatusNotAndStatusNot(userId,1,StatusBill.finished.toString(),StatusBill.cancel.toString());
        entities.forEach(entity-> {
            List<BillReceiptDTO> billReceiptDTOS=new ArrayList<>();
            List<ProductBillEntity> prodBills = productBillRepository.findByBillId(entity.getId());
            prodBills.forEach(prodBill->{
                ProductDetail detail = ProductDetail.converter(prodBill.getProductDetail(),
                        imageService.findByColorIdAndProductIdAndParent(prodBill.getProductDetail().getColor().getId(), prodBill.getProductDetail().getProduct().getId()),0);
                detail.setPrice(prodBill.getPrice());
                billReceiptDTOS.add(new BillReceiptDTO(prodBill.getQuantity(),detail));
            });
            dtos.add(new BillDTO(entity.getId(),billReceiptDTOS,entity.getTotalprice(),entity.getStatus(),entity.getAddress(),entity.getCreatedAt(),entity.getModifiedAt()));
        });
        return dtos;
    }

    @Override
    public List<BillDTO> findAllHisOrderByUser(Long userId) {
        List<BillDTO> dtos=new ArrayList<>();
        List<BillEntity> entities=billRepository.findByUserIdAndBillTypeAndStatus(userId,1,StatusBill.finished.toString());
        entities.forEach(entity-> {
            List<BillReceiptDTO> billReceiptDTOS=new ArrayList<>();
            List<ProductBillEntity> prodBills = productBillRepository.findByBillId(entity.getId());
            prodBills.forEach(prodBill->{
                ProductDetail detail = ProductDetail.converter(prodBill.getProductDetail(),
                        imageService.findByColorIdAndProductIdAndParent(prodBill.getProductDetail().getColor().getId(), prodBill.getProductDetail().getProduct().getId()),0);
                detail.setPrice(prodBill.getPrice());
                Optional<CommentEntity> comment=commentRepository.findByUserIdAndAndProductId(userId,prodBill.getProductDetail().getProduct().getId());
                if(comment.isPresent()){
                    detail.setRate(true);
                }
                billReceiptDTOS.add(new BillReceiptDTO(prodBill.getQuantity(),detail));
            });
                dtos.add(new BillDTO(entity.getId(),billReceiptDTOS,entity.getTotalprice(),entity.getStatus(),entity.getAddress(),entity.getCreatedAt(),entity.getModifiedAt()));
        });
        return dtos;
    }

    @Override
    public List<BillDTO> findAllCancelBillByUser(Long userId) {
        List<BillDTO> dtos=new ArrayList<>();
        List<BillEntity> entities=billRepository.findByUserIdAndBillTypeAndStatus(userId,1,StatusBill.cancel.toString());
        entities.forEach(entity-> {
            List<BillReceiptDTO> billReceiptDTOS=new ArrayList<>();
            List<ProductBillEntity> prodBills = productBillRepository.findByBillId(entity.getId());
            prodBills.forEach(prodBill->{
                ProductDetail detail = ProductDetail.converter(prodBill.getProductDetail(),
                        imageService.findByColorIdAndProductIdAndParent(prodBill.getProductDetail().getColor().getId(), prodBill.getProductDetail().getProduct().getId()),0);
                detail.setPrice(prodBill.getPrice());
                billReceiptDTOS.add(new BillReceiptDTO(prodBill.getQuantity(),detail));
            });
            dtos.add(new BillDTO(entity.getId(),billReceiptDTOS,entity.getTotalprice(),entity.getStatus(),entity.getAddress(),entity.getCreatedAt(),entity.getModifiedAt()));
        });
        return dtos;
    }

    @Override
    public void changePaying() {
        UserEntity user=userRepository.findByEmailAndStatusAndEnabled(SecurityContextHolder.getContext().getAuthentication().getName(),1,true)
                .orElseThrow(()->new RequestException("Bạn chưa đăng nhập","login"));
        BillEntity bill=billRepository.findByUserIdAndBillType(user.getId(),0);
        bill.setPaying(0);
        billRepository.save(bill);
    }

    @Override
    public Map<String,Object> getAll(int page,boolean now) {
        Map<String,Object> map=new HashMap<>();
        Pageable pageable= PageRequest.of(page-1,10);
        Page<BillEntity> entities=now?billRepository.findAllByBillTypeAndMonthNow(pageable)
                :billRepository.findAllByBillTypeOrderByCreatedAtDesc(1,pageable);
        List<BillResponse> responses=new ArrayList<>();
        entities.getContent().forEach(e->responses.add(BillResponse.converter(e)));
        map.put("totalItems",entities.getTotalElements());
        map.put("bills",responses);
        return map;
    }

    @Override
    public List<BillDetailResponse> findByBillId(Long billId) {
        List<BillDetailResponse> responses=new ArrayList<>();
        List<ProductBillEntity> list=productBillRepository.findByBillId(billId);
        list.forEach(e->responses.add(BillDetailResponse.converter(e,
                imageService.findByColorIdAndProductIdAndParent(e.getProductDetail().getColor().getId(),e.getProductDetail().getProduct().getId()))));
        return responses;
    }

    @Override
    public void updateBill(Long id,String status,HttpServletRequest request) {
        BillEntity bill=billRepository.findById(id).orElseThrow(()->new ApiRequestException("Hóa đơn không tồn tại!"));
        bill.setStatus(status);
        billRepository.save(bill);
        List<BillReceiptDTO> billReceiptDTOS = new ArrayList<>();
        List<ProductBillEntity> entities = productBillRepository.findByBillId(bill.getId());
        entities.forEach(e->{
            ProductDetail detail = ProductDetail.converter(e.getProductDetail(),
                    imageService.findByColorIdAndProductIdAndParent(e.getProductDetail().getColor().getId(),e.getProductDetail().getProduct().getId()),0);
            detail.setPrice(e.getPrice());
            billReceiptDTOS.add(new BillReceiptDTO(e.getQuantity(),detail));
        });

        try {
            if(status.equals(StatusBill.delivering.toString())){
                sendMailService.sendMailOrder2(bill.getUser(),billReceiptDTOS,request);
            }else{
                String subject = "";
                String htmlMsg = "";
                if(status.equals(StatusBill.waiting.toString())){
                    subject = "Thông báo xác nhận đơn hàng!";
                    htmlMsg = "<p><strong>Cám ơn bạn đã mua hàng!</strong></p><p>Bên mình đã nhận được đơn hàng của bạn và đang trong quá trình xử lý, bên mình sẽ giao sản phẩm đến với bạn sớm nhất.</p><a href='[[URL]]' target='_self'>Xem đơn hàng</a><p>Cảm ơn !</p>";
                }else if (status.equals(StatusBill.finished.toString())){
                    String pdf = ExportBill(id);
                    String fileName=firebaseService.upLoadFileText(pdf);
                    String url = firebaseService.getUrl(fileName,"export");
                    subject = "Xác nhận thanh toán!";
                    htmlMsg = "<p><strong>Thanh toán đơn hàng thành công!</strong></p><p>Xin chào "+bill.getUser().getFirstName()+", đơn hàng của bạn đã được thanh toán thành công. Cảm ơn bạn đã mua hàng.</p><a href='[[URL]]' target='_self'>Xem đơn hàng</a> hoặc <a id='downloadpdf' href='"+url+"' title='bill.pdf' >Hóa đơn mua hàng</a><p>Cảm ơn !</p>";
                }else {
                    subject = "Thông báo hủy đơn hàng!";
                    htmlMsg = "<p><strong>Hủy đơn hàng thành công!</strong></p><p>Xin chào "+bill.getUser().getFirstName()+", đơn hàng của bạn đã được hủy. Cảm ơn bạn đã sử dụng dịch vụ của bên mình, mong rằng có thể được phụ vụ bạn trong lần tới.</p><a href='[[URL]]' target='_self'>Xem đơn hàng</a><p>Cảm ơn !</p>";
                }

                sendMailService.sendMailOrder(bill.getUser(),billReceiptDTOS,bill,subject,htmlMsg,request);
            }
        } catch (MessagingException |UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String ExportBill(Long billId) {
        UserEntity user = userRepository.findByEmailAndStatusAndEnabled(SecurityContextHolder.getContext().getAuthentication().getName(),1,true)
                .orElseThrow(()-> new ApiRequestException("Bạn chưa đăng nhập"));
        BillEntity bill = billRepository.findById(billId).orElseThrow(()-> new ApiRequestException("Hóa đơn không tồn tại"));
        List<ProductBillEntity> entities = productBillRepository.findByBillId(bill.getId());
        List<ProductExportRequest> exportRequests = new ArrayList<>();
        AtomicReference<Integer> index = new AtomicReference<>(0);
        entities.forEach(e->{
            index.updateAndGet(v -> v + 1);
            exportRequests.add(ProductExportRequest.converterBill(index.get(),e,e.getQuantity()));
        });
        String str = "";
        try {
            // Ten template
            String templateName = "Bill_report.jrxml";
            // Tao du lieu dataSources
            Map<String, Object> dataSources = new HashMap<>();
            JRBeanCollectionDataSource dataTable = new JRBeanCollectionDataSource(exportRequests, false);
            dataSources.put("ListData", dataTable);
            // Tao params
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("p_date", String.valueOf(LocalDate.now().getDayOfMonth()));
            parameters.put("p_month", String.valueOf(LocalDate.now().getMonthValue()));
            parameters.put("p_year", String.valueOf(LocalDate.now().getYear()));
            parameters.put("p_fullName", user.getFullName());
            parameters.put("p_sdt", user.getPhone());
            parameters.put("p_diaChi",bill.getAddress());
            parameters.put("p_totalPrice",bill.getTotalprice());
            parameters.put("p_totalPriceVN", StringUtils.convertMoneyToString(bill.getTotalprice().longValue()));
            parameters.put("p_payment",bill.getPayment().equals("off")?"Tiền mặt":"Chuyển khoản");
            // Xuat bao cao
            str = exportReport.createPdfReport(templateName, dataSources, parameters);
            System.out.println("Xuat bao cao thanh cong");
        } catch (final Exception e) {
            e.printStackTrace();
            System.out.println("Xuat bao cao that bai");
        }
        return str;
    }
}
