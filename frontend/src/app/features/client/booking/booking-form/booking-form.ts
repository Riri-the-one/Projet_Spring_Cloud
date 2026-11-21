
import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';

interface BookingResponse {
  id: number;
  eventId: number;
  eventTitle: string;
  customerId: number;
  quantity: number;
  unitPrice: number;
  totalPrice: number;
  status: string;
  createdAt: string;
}

interface PaymentResponse {
  id: number;
  status: string;
}

@Component({
  selector: 'app-booking-form',
  imports: [FormsModule, RouterLink],
  templateUrl: './booking-form.html',
  styleUrl: './booking-form.css'
})
export class BookingForm {
  private http = inject(HttpClient);
  private route = inject(ActivatedRoute);

  eventId = Number(this.route.snapshot.queryParamMap.get('eventId'));
  customerId = 1;
  quantity = 1;

  loading = signal(false);
  paying = signal(false);
  error = signal('');
  paymentMessage = signal('');
  booking = signal<BookingResponse | null>(null);

  reserve(): void {
    if (!Number.isInteger(this.eventId) || this.eventId <= 0) {
      this.error.set('Événement invalide.');
      return;
    }

    if (!Number.isInteger(this.customerId) || this.customerId <= 0) {
      this.error.set('Identifiant client invalide.');
      return;
    }

    if (!Number.isInteger(this.quantity) ||
      this.quantity < 1 || this.quantity > 10) {
      this.error.set('Choisissez entre 1 et 10 places.');
      return;
    }

    this.loading.set(true);
    this.error.set('');

    this.http.post<BookingResponse>('/api/bookings', {
      eventId: this.eventId,
      customerId: this.customerId,
      quantity: this.quantity
    }).subscribe({
      next: (result) => {
        this.booking.set(result);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Impossible de créer la réservation.');
        this.loading.set(false);
      }
    });
  }

  pay(success: boolean): void {
    const reservation = this.booking();

    if (!reservation || this.paying()) {
      return;
    }

    this.paying.set(true);
    this.paymentMessage.set('');

    this.http.post<PaymentResponse>('/api/payments', {
      bookingId: reservation.id,
      amount: reservation.totalPrice,
      success
    }).subscribe({
      next: (payment) => {
        this.paymentMessage.set(
          'Paiement enregistré. Statut : ' + payment.status
        );
        this.paying.set(false);
      },
      error: () => {
        this.paymentMessage.set(
          'Impossible de traiter le paiement.'
        );
        this.paying.set(false);
      }
    });
  }
}
