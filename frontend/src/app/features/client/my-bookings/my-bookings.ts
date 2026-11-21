
import { Component, inject, OnInit, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { RouterLink } from '@angular/router';

interface Booking {
  id: number;
  eventId: number;
  eventTitle: string;
  customerId: number;
  quantity: number;
  totalPrice: number;
  status: string;
  createdAt: string;
}

@Component({
  selector: 'app-my-bookings',
  imports: [RouterLink],
  templateUrl: './my-bookings.html',
  styleUrl: './my-bookings.css'
})
export class MyBookings implements OnInit {
  private readonly http = inject(HttpClient);

  // Temporaire : sera remplacé par le client connecté via Keycloak
  readonly customerId = 1;

  readonly bookings = signal<Booking[]>([]);
  readonly loading = signal(true);
  readonly error = signal('');

  ngOnInit(): void {
    this.loadBookings();
  }

  loadBookings(): void {
    this.loading.set(true);
    this.error.set('');

    const params = new HttpParams()
      .set('customerId', this.customerId.toString());

    this.http.get<Booking[]>('/api/bookings', { params }).subscribe({
      next: (result) => {
        this.bookings.set(result);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Impossible de charger les réservations.');
        this.loading.set(false);
      }
    });
  }
}
