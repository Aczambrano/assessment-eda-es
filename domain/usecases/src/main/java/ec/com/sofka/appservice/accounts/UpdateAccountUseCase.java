package ec.com.sofka.appservice.accounts;

import ec.com.sofka.aggregate.Customer;
import ec.com.sofka.appservice.accounts.request.UpdateAccountRequest;
import ec.com.sofka.appservice.accounts.response.UpdateAccountResponse;
import ec.com.sofka.appservice.gateway.IAccountRepository;
import ec.com.sofka.appservice.gateway.IEventStore;
import ec.com.sofka.appservice.gateway.dto.AccountDTO;
import ec.com.sofka.generics.interfaces.IUseCase;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class UpdateAccountUseCase implements IUseCase<UpdateAccountRequest, UpdateAccountResponse> {

    private final IAccountRepository accountRepository;
    private final IEventStore eventRepository;

    public UpdateAccountUseCase(IAccountRepository accountRepository, IEventStore eventRepository) {
        this.accountRepository = accountRepository;
        this.eventRepository = eventRepository;
    }


    @Override
    public Mono<UpdateAccountResponse> execute(UpdateAccountRequest request) {
        return eventRepository.findAggregate(request.getAggregateId()) // Retorna Flux<DomainEvent>
                .collectList() // Convertimos Flux a Mono<List<DomainEvent>>
                .flatMap(events -> {
                    // Reconstruir el aggregate de manera reactiva
                    return Customer.from(request.getAggregateId(), Flux.fromIterable(events))
                            .flatMap(customer -> {
                                // Actualizar datos en el cliente
                                customer.updateAccount(
                                        customer.getAccount().getId().getValue(),
                                        request.getBalance(),
                                        request.getNumber(),
                                        request.getCustomerName(),
                                        request.getStatus()
                                );

                                // Actualizar la cuenta en el repositorio de manera reactiva
                                AccountDTO accountDTO = new AccountDTO(
                                        customer.getAccount().getId().getValue(),
                                        request.getCustomerName(),
                                        request.getNumber(),
                                        customer.getAccount().getBalance().getValue(),
                                        customer.getAccount().getStatus().getValue()
                                );

                                return accountRepository.update(accountDTO) // Realizar actualización reactiva
                                        .flatMap(result -> {
                                            // Guardar los eventos no comprometidos de manera reactiva
                                            return Flux.fromIterable(customer.getUncommittedEvents())
                                                    .flatMap(eventRepository::save) // Guardar cada evento de forma reactiva
                                                    .then(Mono.defer(() -> {
                                                        customer.markEventsAsCommitted(); // Marcar eventos como comprometidos
                                                        return Mono.just(new UpdateAccountResponse(
                                                                request.getAggregateId(),
                                                                result.getId(),
                                                                result.getAccountNumber(),
                                                                result.getName(),
                                                                result.getStatus()
                                                        ));
                                                    }));
                                        });
                            });
                })
                .defaultIfEmpty(new UpdateAccountResponse()); // Manejar el caso donde no hay resultado
    }


}
